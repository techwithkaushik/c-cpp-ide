#if defined(__APPLE__)
.section __TEXT,__const
#else
.section .rodata
#endif
.balign 16
.global code_studio_bootstrap_start
.type code_studio_bootstrap_start, %object
code_studio_bootstrap_start:
.incbin BOOTSTRAP_ZIP_PATH
.global code_studio_bootstrap_end
.type code_studio_bootstrap_end, %object
code_studio_bootstrap_end:
.size code_studio_bootstrap_start, code_studio_bootstrap_end-code_studio_bootstrap_start
