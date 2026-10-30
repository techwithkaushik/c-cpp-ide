cmake_minimum_required(VERSION 3.22.1)
project(code_studio_bootstrap LANGUAGES CXX ASM)

if(NOT DEFINED BOOTSTRAP_ZIP_PATH OR BOOTSTRAP_ZIP_PATH STREQUAL "")
    message(FATAL_ERROR "BOOTSTRAP_ZIP_PATH is required; refusing to build without the ABI-specific bootstrap archive.")
endif()
if(NOT EXISTS "${BOOTSTRAP_ZIP_PATH}")
    message(FATAL_ERROR "ABI-specific bootstrap archive does not exist: ${BOOTSTRAP_ZIP_PATH}")
endif()

add_library(termux-bootstrap SHARED
    bootstrap_bridge.cpp
    bootstrap_zip.S
)
target_compile_features(termux-bootstrap PRIVATE cxx_std_20)
target_compile_options(termux-bootstrap PRIVATE -Wall -Wextra -Wpedantic)
target_compile_definitions(termux-bootstrap PRIVATE BOOTSTRAP_ZIP_PATH="${BOOTSTRAP_ZIP_PATH}")
