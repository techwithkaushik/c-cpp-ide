package com.techwithkaushik.cppide

/** Centralized setup labels and persisted Linux environment keys. */
internal object LinuxSetupConfig {
    const val DOWNLOAD_METHOD = "Download Ubuntu from Internet"
    const val IMPORT_METHOD = "Use Downloaded Rootfs from Storage"

    const val DOWNLOADING = "Downloading Ubuntu…"
    const val INTERRUPTED_DOWNLOAD = "The previous download was interrupted. Retry it or select an archive from storage."
    const val VERIFYING = "Checking saved Ubuntu archive…"
    const val EXTRACTING = "Extracting Ubuntu…"
    const val ROOTFS_FOUND = "Existing Ubuntu filesystem found."
    const val PREPARING = "Preparing Linux workspace…"
    const val VALIDATING = "Checking Linux runtime…"
    const val SELECT_ARCHIVE = "Select a Linux rootfs archive to continue."
    const val ARCHIVE_MISSING = "No installed Linux filesystem or archive was found."
    const val RUNTIME_MISSING = "Linux files are preserved, but the Android-compatible PRoot runtime is missing or the shell test failed."
    const val SETUP_FAILED = "Linux setup failed. Existing files were preserved."

    const val DISTRO_ID = "ubuntu"
    const val ENV_FILE_NAME = "environment.properties"
    const val ENV_DISTRO = "DISTRO"
    const val ENV_ROOTFS = "ROOTFS"
    const val ENV_HOME = "HOME"
    const val ENV_WORKSPACE = "WORKSPACE"
    const val ENV_TMPDIR = "TMPDIR"
    const val ENV_PATH = "PATH"
    const val HOME_PATH = "/home/ide"
    const val WORKSPACE_PATH = "/workspace"
    const val TMP_PATH = "/tmp"
    const val DEFAULT_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
}
