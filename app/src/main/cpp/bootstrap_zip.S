.section .rodata
.balign 16
.global code_studio_bootstrap_start
code_studio_bootstrap_start:
.incbin BOOTSTRAP_ZIP_PATH
.global code_studio_bootstrap_end
code_studio_bootstrap_end:
