cmake_minimum_required(VERSION 3.22.1)
project(cppide_native LANGUAGES CXX)

add_library(cppide_native SHARED
    native_bridge.cpp
)

target_compile_features(cppide_native PRIVATE cxx_std_20)
target_compile_options(cppide_native PRIVATE
    -Wall
    -Wextra
    -Wpedantic
)

find_library(android_log log)
target_link_libraries(cppide_native PRIVATE ${android_log})
