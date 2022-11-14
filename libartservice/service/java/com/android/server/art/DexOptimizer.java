/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.art;

import static com.android.server.art.GetDexoptNeededResult.ArtifactsLocation;
import static com.android.server.art.OutputArtifacts.PermissionSettings;
import static com.android.server.art.ProfilePath.TmpProfilePath;
import static com.android.server.art.Utils.Abi;
import static com.android.server.art.model.ArtFlags.OptimizeFlags;
import static com.android.server.art.model.OptimizeResult.DexContainerFileOptimizeResult;

import android.R;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.os.CancellationSignal;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.os.SystemProperties;
import android.os.UserManager;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.art.model.ArtFlags;
import com.android.server.art.model.DetailedDexInfo;
import com.android.server.art.model.OptimizeParams;
import com.android.server.art.model.OptimizeResult;
import com.android.server.pm.pkg.AndroidPackage;
import com.android.server.pm.pkg.PackageState;

import dalvik.system.DexFile;

import com.google.auto.value.AutoValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** @hide */
public abstract class DexOptimizer<DexInfoType extends DetailedDexInfo> {
    private static final String TAG = "DexOptimizer";

    @NonNull protected final Injector mInjector;
    @NonNull protected final PackageState mPkgState;
    /** This is always {@code mPkgState.getAndroidPackage()} and guaranteed to be non-null. */
    @NonNull protected final AndroidPackage mPkg;
    @NonNull protected final OptimizeParams mParams;
    @NonNull protected final CancellationSignal mCancellationSignal;

    protected DexOptimizer(@NonNull Injector injector, @NonNull PackageState pkgState,
            @NonNull AndroidPackage pkg, @NonNull OptimizeParams params,
            @NonNull CancellationSignal cancellationSignal) {
        mInjector = injector;
        mPkgState = pkgState;
        mPkg = pkg;
        mParams = params;
        mCancellationSignal = cancellationSignal;
        if (pkgState.getAppId() < 0) {
            throw new IllegalStateException(
                    "Package '" + pkgState.getPackageName() + "' has invalid app ID");
        }
    }

    /**
     * DO NOT use this method directly. Use {@link
     * ArtManagerLocal#optimizePackage(PackageManagerLocal.FilteredSnapshot, String,
     * OptimizeParams)}.
     */
    @NonNull
    public final List<DexContainerFileOptimizeResult> dexopt() throws RemoteException {
        List<DexContainerFileOptimizeResult> results = new ArrayList<>();

        for (DexInfoType dexInfo : getDexInfoList()) {
            ProfilePath profile = null;
            boolean succeeded = true;
            try {
                if (!isOptimizable(dexInfo)) {
                    continue;
                }

                String compilerFilter = adjustCompilerFilter(mParams.getCompilerFilter(), dexInfo);
                if (compilerFilter.equals(OptimizeParams.COMPILER_FILTER_NOOP)) {
                    continue;
                }

                boolean needsToBeShared = needsToBeShared(dexInfo);
                boolean isOtherReadable = true;
                // If true, implies that the profile has changed since the last compilation.
                boolean profileMerged = false;
                if (DexFile.isProfileGuidedCompilerFilter(compilerFilter)) {
                    if (needsToBeShared) {
                        profile = initReferenceProfile(dexInfo);
                    } else {
                        Pair<ProfilePath, Boolean> pair = getOrInitReferenceProfile(dexInfo);
                        if (pair != null) {
                            profile = pair.first;
                            isOtherReadable = pair.second;
                        }
                        ProfilePath mergedProfile = mergeProfiles(dexInfo, profile);
                        if (mergedProfile != null) {
                            if (profile != null && profile.getTag() == ProfilePath.tmpProfilePath) {
                                mInjector.getArtd().deleteProfile(profile);
                            }
                            profile = mergedProfile;
                            isOtherReadable = false;
                            profileMerged = true;
                        }
                    }
                    if (profile == null) {
                        // A profile guided optimization with no profile is essentially 'verify',
                        // and dex2oat already makes this transformation. However, we need to
                        // explicitly make this transformation here to guide the later decisions
                        // such as whether the artifacts can be public and whether dexopt is needed.
                        compilerFilter = needsToBeShared
                                ? ReasonMapping.getCompilerFilterForShared()
                                : "verify";
                    }
                }
                boolean isProfileGuidedCompilerFilter =
                        DexFile.isProfileGuidedCompilerFilter(compilerFilter);
                Utils.check(isProfileGuidedCompilerFilter == (profile != null));

                boolean canBePublic = (!isProfileGuidedCompilerFilter || isOtherReadable)
                        && isDexFilePublic(dexInfo);
                Utils.check(Utils.implies(needsToBeShared, canBePublic));
                PermissionSettings permissionSettings = getPermissionSettings(dexInfo, canBePublic);

                DexoptOptions dexoptOptions =
                        getDexoptOptions(dexInfo, isProfileGuidedCompilerFilter);

                for (Abi abi : getAllAbis(dexInfo)) {
                    @OptimizeResult.OptimizeStatus int status = OptimizeResult.OPTIMIZE_SKIPPED;
                    long wallTimeMs = 0;
                    long cpuTimeMs = 0;
                    long sizeBytes = 0;
                    long sizeBeforeBytes = 0;
                    try {
                        var target = DexoptTarget.<DexInfoType>builder()
                                                      .setDexInfo(dexInfo)
                                                      .setIsa(abi.isa())
                                                      .setIsInDalvikCache(isInDalvikCache())
                                                      .setCompilerFilter(compilerFilter)
                                                      .build();
                        var options =
                                GetDexoptNeededOptions.builder()
                                        .setProfileMerged(profileMerged)
                                        .setFlags(mParams.getFlags())
                                        .setNeedsToBePublic(needsToBeShared)
                                        .build();

                        GetDexoptNeededResult getDexoptNeededResult =
                                getDexoptNeeded(target, options);

                        if (!getDexoptNeededResult.isDexoptNeeded) {
                            continue;
                        }

                        IArtdCancellationSignal artdCancellationSignal =
                                mInjector.getArtd().createCancellationSignal();
                        mCancellationSignal.setOnCancelListener(() -> {
                            try {
                                artdCancellationSignal.cancel();
                            } catch (RemoteException e) {
                                Log.e(TAG, "An error occurred when sending a cancellation signal",
                                        e);
                            }
                        });

                        DexoptResult dexoptResult = dexoptFile(target, profile,
                                getDexoptNeededResult, permissionSettings,
                                mParams.getPriorityClass(), dexoptOptions, artdCancellationSignal);
                        status = dexoptResult.cancelled ? OptimizeResult.OPTIMIZE_CANCELLED
                                                        : OptimizeResult.OPTIMIZE_PERFORMED;
                        wallTimeMs = dexoptResult.wallTimeMs;
                        cpuTimeMs = dexoptResult.cpuTimeMs;
                        sizeBytes = dexoptResult.sizeBytes;
                        sizeBeforeBytes = dexoptResult.sizeBeforeBytes;

                        if (status == OptimizeResult.OPTIMIZE_CANCELLED) {
                            return results;
                        }
                    } catch (ServiceSpecificException e) {
                        // Log the error and continue.
                        Log.e(TAG,
                                String.format("Failed to dexopt [packageName = %s, dexPath = %s, "
                                                + "isa = %s, classLoaderContext = %s]",
                                        mPkgState.getPackageName(), dexInfo.dexPath(), abi.isa(),
                                        dexInfo.classLoaderContext()),
                                e);
                        status = OptimizeResult.OPTIMIZE_FAILED;
                    } finally {
                        results.add(new DexContainerFileOptimizeResult(dexInfo.dexPath(),
                                abi.isPrimaryAbi(), abi.name(), compilerFilter, status, wallTimeMs,
                                cpuTimeMs, sizeBytes, sizeBeforeBytes));
                        if (status != OptimizeResult.OPTIMIZE_SKIPPED
                                && status != OptimizeResult.OPTIMIZE_PERFORMED) {
                            succeeded = false;
                        }
                        // Make sure artd does not leak even if the caller holds
                        // `mCancellationSignal` forever.
                        mCancellationSignal.setOnCancelListener(null);
                    }
                }

                if (profile != null && succeeded) {
                    if (profile.getTag() == ProfilePath.tmpProfilePath) {
                        // Commit the profile only if dexopt succeeds.
                        if (commitProfileChanges(profile.getTmpProfilePath())) {
                            profile = null;
                        }
                    }
                    if (profileMerged) {
                        // Note that this is just an optimization, to reduce the amount of data that
                        // the runtime writes on every profile save. The profile merge result on the
                        // next run won't change regardless of whether the cleanup is done or not
                        // because profman only looks at the diff.
                        // A caveat is that it may delete more than what has been merged, if the
                        // runtime writes additional entries between the merge and the cleanup, but
                        // this is fine because the runtime writes all JITed classes and methods on
                        // every save and the additional entries will likely be written back on the
                        // next save.
                        cleanupCurProfiles(dexInfo);
                    }
                }
            } finally {
                if (profile != null && profile.getTag() == ProfilePath.tmpProfilePath) {
                    mInjector.getArtd().deleteProfile(profile);
                }
            }
        }

        return results;
    }

    @NonNull
    private String adjustCompilerFilter(
            @NonNull String targetCompilerFilter, @NonNull DexInfoType dexInfo) {
        if (mInjector.isSystemUiPackage(mPkgState.getPackageName())) {
            String systemUiCompilerFilter = getSystemUiCompilerFilter();
            if (!systemUiCompilerFilter.isEmpty()) {
                return systemUiCompilerFilter;
            }
        }

        // We force vmSafeMode on debuggable apps as well:
        //  - the runtime ignores their compiled code
        //  - they generally have lots of methods that could make the compiler used run out of
        //    memory (b/130828957)
        // Note that forcing the compiler filter here applies to all compilations (even if they
        // are done via adb shell commands). This is okay because the runtime will ignore the
        // compiled code anyway.
        if (mPkg.isVmSafeMode() || mPkg.isDebuggable()) {
            return DexFile.getSafeModeCompilerFilter(targetCompilerFilter);
        }

        // We cannot do AOT compilation if we don't have a valid class loader context.
        if (dexInfo.classLoaderContext() == null) {
            return DexFile.isOptimizedCompilerFilter(targetCompilerFilter) ? "verify"
                                                                           : targetCompilerFilter;
        }

        // This application wants to use the embedded dex in the APK, rather than extracted or
        // locally compiled variants, so we only verify it.
        // "verify" does not prevent dex2oat from extracting the dex code, but in practice, dex2oat
        // won't extract the dex code because the APK is uncompressed, and the assumption is that
        // such applications always use uncompressed APKs.
        if (mPkg.isUseEmbeddedDex()) {
            return DexFile.isOptimizedCompilerFilter(targetCompilerFilter) ? "verify"
                                                                           : targetCompilerFilter;
        }

        return targetCompilerFilter;
    }

    @NonNull
    private String getSystemUiCompilerFilter() {
        String compilerFilter = SystemProperties.get("dalvik.vm.systemuicompilerfilter");
        if (!compilerFilter.isEmpty() && !Utils.isValidArtServiceCompilerFilter(compilerFilter)) {
            throw new IllegalStateException(
                    "Got invalid compiler filter '" + compilerFilter + "' for System UI");
        }
        return compilerFilter;
    }

    /**
     * Gets the existing reference profile if exists, or initializes a reference profile from an
     * external profile.
     *
     * @return A pair where the first element is the found or initialized profile, and the second
     *         element is true if the profile is readable by others. Or null if there is no
     *         reference profile or external profile to use.
     */
    @Nullable
    private Pair<ProfilePath, Boolean> getOrInitReferenceProfile(@NonNull DexInfoType dexInfo)
            throws RemoteException {
        ProfilePath refProfile = buildRefProfilePath(dexInfo);
        try {
            if (mInjector.getArtd().isProfileUsable(refProfile, dexInfo.dexPath())) {
                boolean isOtherReadable = mInjector.getArtd().getProfileVisibility(refProfile)
                        == FileVisibility.OTHER_READABLE;
                return Pair.create(refProfile, isOtherReadable);
            }
        } catch (ServiceSpecificException e) {
            Log.e(TAG,
                    "Failed to use the existing reference profile "
                            + AidlUtils.toString(refProfile),
                    e);
        }

        ProfilePath initializedProfile = initReferenceProfile(dexInfo);
        return initializedProfile != null ? Pair.create(initializedProfile, true) : null;
    }

    @NonNull
    private DexoptOptions getDexoptOptions(
            @NonNull DexInfoType dexInfo, boolean isProfileGuidedFilter) {
        DexoptOptions dexoptOptions = new DexoptOptions();
        dexoptOptions.compilationReason = mParams.getReason();
        dexoptOptions.targetSdkVersion = mPkg.getTargetSdkVersion();
        dexoptOptions.debuggable = mPkg.isDebuggable() || isAlwaysDebuggable();
        // Generating a meaningful app image needs a profile to determine what to include in the
        // image. Otherwise, the app image will be nearly empty.
        dexoptOptions.generateAppImage =
                isProfileGuidedFilter && isAppImageAllowed(dexInfo) && isAppImageEnabled();
        dexoptOptions.hiddenApiPolicyEnabled = isHiddenApiPolicyEnabled();
        return dexoptOptions;
    }

    private boolean isAlwaysDebuggable() {
        return SystemProperties.getBoolean("dalvik.vm.always_debuggable", false /* def */);
    }

    private boolean isAppImageEnabled() {
        return !SystemProperties.get("dalvik.vm.appimageformat").isEmpty();
    }

    private boolean isHiddenApiPolicyEnabled() {
        if (mPkg.isSignedWithPlatformKey()) {
            return false;
        }
        if (mPkgState.isSystem() || mPkgState.isUpdatedSystemApp()) {
            // TODO(b/236389629): Check whether the app is in hidden api whitelist.
            return !mPkg.isUsesNonSdkApi();
        }
        return true;
    }

    @NonNull
    GetDexoptNeededResult getDexoptNeeded(@NonNull DexoptTarget<DexInfoType> target,
            @NonNull GetDexoptNeededOptions options) throws RemoteException {
        int dexoptTrigger = getDexoptTrigger(target, options);

        // The result should come from artd even if all the bits of `dexoptTrigger` are set
        // because the result also contains information about the usable VDEX file.
        // Note that the class loader context can be null. In that case, we intentionally pass the
        // null value down to lower levels to indicate that the class loader context check should be
        // skipped because we are only going to verify the dex code (see `adjustCompilerFilter`).
        GetDexoptNeededResult result = mInjector.getArtd().getDexoptNeeded(
                target.dexInfo().dexPath(), target.isa(), target.dexInfo().classLoaderContext(),
                target.compilerFilter(), dexoptTrigger);

        return result;
    }

    int getDexoptTrigger(@NonNull DexoptTarget<DexInfoType> target,
            @NonNull GetDexoptNeededOptions options) throws RemoteException {
        if ((options.flags() & ArtFlags.FLAG_FORCE) != 0) {
            return DexoptTrigger.COMPILER_FILTER_IS_BETTER | DexoptTrigger.COMPILER_FILTER_IS_SAME
                    | DexoptTrigger.COMPILER_FILTER_IS_WORSE
                    | DexoptTrigger.PRIMARY_BOOT_IMAGE_BECOMES_USABLE;
        }

        if ((options.flags() & ArtFlags.FLAG_SHOULD_DOWNGRADE) != 0) {
            return DexoptTrigger.COMPILER_FILTER_IS_WORSE;
        }

        int dexoptTrigger = DexoptTrigger.COMPILER_FILTER_IS_BETTER
                | DexoptTrigger.PRIMARY_BOOT_IMAGE_BECOMES_USABLE;
        if (options.profileMerged()) {
            dexoptTrigger |= DexoptTrigger.COMPILER_FILTER_IS_SAME;
        }

        ArtifactsPath existingArtifactsPath = AidlUtils.buildArtifactsPath(
                target.dexInfo().dexPath(), target.isa(), target.isInDalvikCache());

        if (options.needsToBePublic()
                && mInjector.getArtd().getArtifactsVisibility(existingArtifactsPath)
                        == FileVisibility.NOT_OTHER_READABLE) {
            // Typically, this happens after an app starts being used by other apps.
            // This case should be the same as force as we have no choice but to trigger a new
            // dexopt.
            dexoptTrigger |=
                    DexoptTrigger.COMPILER_FILTER_IS_SAME | DexoptTrigger.COMPILER_FILTER_IS_WORSE;
        }

        return dexoptTrigger;
    }

    private DexoptResult dexoptFile(@NonNull DexoptTarget<DexInfoType> target,
            @Nullable ProfilePath profile, @NonNull GetDexoptNeededResult getDexoptNeededResult,
            @NonNull PermissionSettings permissionSettings, @PriorityClass int priorityClass,
            @NonNull DexoptOptions dexoptOptions, IArtdCancellationSignal artdCancellationSignal)
            throws RemoteException {
        OutputArtifacts outputArtifacts = AidlUtils.buildOutputArtifacts(target.dexInfo().dexPath(),
                target.isa(), target.isInDalvikCache(), permissionSettings);

        VdexPath inputVdex =
                getInputVdex(getDexoptNeededResult, target.dexInfo().dexPath(), target.isa());

        DexMetadataPath dmFile = getDmFile(target.dexInfo());
        if (dmFile != null
                && ReasonMapping.REASONS_FOR_INSTALL.contains(dexoptOptions.compilationReason)) {
            // If the DM file is passed to dex2oat, then add the "-dm" suffix to the reason (e.g.,
            // "install-dm").
            // Note that this only applies to reasons for app install because the goal is to give
            // Play a signal that a DM file is downloaded at install time. We actually pass the DM
            // file regardless of the compilation reason, but we don't append a suffix when the
            // compilation reason is not a reason for app install.
            // Also note that the "-dm" suffix does NOT imply anything in the DM file being used by
            // dex2oat. dex2oat may ignore some contents of the DM file when appropriate. The
            // compilation reason can still be "install-dm" even if dex2oat left all contents of the
            // DM file unused or an empty DM file is passed to dex2oat.
            dexoptOptions.compilationReason = dexoptOptions.compilationReason + "-dm";
        }

        return mInjector.getArtd().dexopt(outputArtifacts, target.dexInfo().dexPath(), target.isa(),
                target.dexInfo().classLoaderContext(), target.compilerFilter(), profile, inputVdex,
                dmFile, priorityClass, dexoptOptions, artdCancellationSignal);
    }

    @Nullable
    private VdexPath getInputVdex(@NonNull GetDexoptNeededResult getDexoptNeededResult,
            @NonNull String dexPath, @NonNull String isa) {
        if (!getDexoptNeededResult.isVdexUsable) {
            return null;
        }
        switch (getDexoptNeededResult.artifactsLocation) {
            case ArtifactsLocation.DALVIK_CACHE:
                return VdexPath.artifactsPath(
                        AidlUtils.buildArtifactsPath(dexPath, isa, true /* isInDalvikCache */));
            case ArtifactsLocation.NEXT_TO_DEX:
                return VdexPath.artifactsPath(
                        AidlUtils.buildArtifactsPath(dexPath, isa, false /* isInDalvikCache */));
            case ArtifactsLocation.DM:
                // The DM file is passed to dex2oat as a separate flag whenever it exists.
                return null;
            default:
                // This should never happen as the value is got from artd.
                throw new IllegalStateException(
                        "Unknown artifacts location " + getDexoptNeededResult.artifactsLocation);
        }
    }

    @Nullable
    private DexMetadataPath getDmFile(@NonNull DexInfoType dexInfo) throws RemoteException {
        DexMetadataPath path = buildDmPath(dexInfo);
        if (path == null) {
            return null;
        }
        try {
            if (mInjector.getArtd().getDmFileVisibility(path) != FileVisibility.NOT_FOUND) {
                return path;
            }
        } catch (ServiceSpecificException e) {
            Log.e(TAG, "Failed to check DM file for " + dexInfo.dexPath(), e);
        }
        return null;
    }

    private boolean commitProfileChanges(@NonNull TmpProfilePath profile) throws RemoteException {
        try {
            mInjector.getArtd().commitTmpProfile(profile);
            return true;
        } catch (ServiceSpecificException e) {
            Log.e(TAG, "Failed to commit profile changes " + AidlUtils.toString(profile.finalPath),
                    e);
            return false;
        }
    }

    @Nullable
    private ProfilePath mergeProfiles(@NonNull DexInfoType dexInfo,
            @Nullable ProfilePath referenceProfile) throws RemoteException {
        OutputProfile output = buildOutputProfile(dexInfo, false /* isPublic */);

        try {
            if (mInjector.getArtd().mergeProfiles(getCurProfiles(dexInfo), referenceProfile, output,
                        List.of(dexInfo.dexPath()), new MergeProfileOptions())) {
                return ProfilePath.tmpProfilePath(output.profilePath);
            }
        } catch (ServiceSpecificException e) {
            Log.e(TAG,
                    "Failed to merge profiles " + AidlUtils.toString(output.profilePath.finalPath),
                    e);
        }

        return null;
    }

    private void cleanupCurProfiles(@NonNull DexInfoType dexInfo) throws RemoteException {
        for (ProfilePath profile : getCurProfiles(dexInfo)) {
            mInjector.getArtd().deleteProfile(profile);
        }
    }

    // Methods to be implemented by child classes.

    /** Returns true if the artifacts should be written to the global dalvik-cache directory. */
    protected abstract boolean isInDalvikCache();

    /** Returns information about all dex files. */
    @NonNull protected abstract List<DexInfoType> getDexInfoList();

    /** Returns true if the given dex file should be optimized. */
    protected abstract boolean isOptimizable(@NonNull DexInfoType dexInfo);

    /**
     * Returns true if the artifacts should be shared with other apps. Note that this must imply
     * {@link #isDexFilePublic(DexInfoType)}.
     */
    protected abstract boolean needsToBeShared(@NonNull DexInfoType dexInfo);

    /**
     * Returns true if the filesystem permission of the dex file has the "read" bit for "others"
     * (S_IROTH).
     */
    protected abstract boolean isDexFilePublic(@NonNull DexInfoType dexInfo);

    /**
     * Returns a reference profile initialized from an external profile (e.g., a DM profile) if
     * one exists, or null otherwise.
     */
    @Nullable
    protected abstract ProfilePath initReferenceProfile(@NonNull DexInfoType dexInfo)
            throws RemoteException;

    /** Returns the permission settings to use for the artifacts of the given dex file. */
    @NonNull
    protected abstract PermissionSettings getPermissionSettings(
            @NonNull DexInfoType dexInfo, boolean canBePublic);

    /** Returns all ABIs that the given dex file should be compiled for. */
    @NonNull protected abstract List<Abi> getAllAbis(@NonNull DexInfoType dexInfo);

    /** Returns the path to the reference profile of the given dex file. */
    @NonNull protected abstract ProfilePath buildRefProfilePath(@NonNull DexInfoType dexInfo);

    /** Returns true if app image (--app-image-fd) is allowed. */
    protected abstract boolean isAppImageAllowed(@NonNull DexInfoType dexInfo);

    /**
     * Returns the data structure that represents the temporary profile to use during processing.
     */
    @NonNull
    protected abstract OutputProfile buildOutputProfile(
            @NonNull DexInfoType dexInfo, boolean isPublic);

    /** Returns the paths to the current profiles of the given dex file. */
    @NonNull protected abstract List<ProfilePath> getCurProfiles(@NonNull DexInfoType dexInfo);

    /**
     * Returns the path to the DM file that should be passed to dex2oat, or null if no DM file
     * should be passed.
     */
    @Nullable protected abstract DexMetadataPath buildDmPath(@NonNull DexInfoType dexInfo);

    @AutoValue
    abstract static class DexoptTarget<DexInfoType extends DetailedDexInfo> {
        abstract @NonNull DexInfoType dexInfo();
        abstract @NonNull String isa();
        abstract boolean isInDalvikCache();
        abstract @NonNull String compilerFilter();

        static <DexInfoType extends DetailedDexInfo> Builder<DexInfoType> builder() {
            return new AutoValue_DexOptimizer_DexoptTarget.Builder<DexInfoType>();
        }

        @AutoValue.Builder
        abstract static class Builder<DexInfoType extends DetailedDexInfo> {
            abstract Builder setDexInfo(@NonNull DexInfoType value);
            abstract Builder setIsa(@NonNull String value);
            abstract Builder setIsInDalvikCache(boolean value);
            abstract Builder setCompilerFilter(@NonNull String value);
            abstract DexoptTarget<DexInfoType> build();
        }
    }

    @AutoValue
    abstract static class GetDexoptNeededOptions {
        abstract @OptimizeFlags int flags();
        abstract boolean profileMerged();
        abstract boolean needsToBePublic();

        static Builder builder() {
            return new AutoValue_DexOptimizer_GetDexoptNeededOptions.Builder();
        }

        @AutoValue.Builder
        abstract static class Builder {
            abstract Builder setFlags(@OptimizeFlags int value);
            abstract Builder setProfileMerged(boolean value);
            abstract Builder setNeedsToBePublic(boolean value);
            abstract GetDexoptNeededOptions build();
        }
    }

    /**
     * Injector pattern for testing purpose.
     *
     * @hide
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PROTECTED)
    public static class Injector {
        @NonNull private final Context mContext;

        public Injector(@NonNull Context context) {
            mContext = context;
        }

        public boolean isSystemUiPackage(@NonNull String packageName) {
            return packageName.equals(mContext.getString(R.string.config_systemUi));
        }

        @NonNull
        public UserManager getUserManager() {
            return Objects.requireNonNull(mContext.getSystemService(UserManager.class));
        }

        @NonNull
        public DexUseManager getDexUseManager() {
            return DexUseManager.getInstance();
        }

        @NonNull
        public IArtd getArtd() {
            return Utils.getArtd();
        }
    }
}
