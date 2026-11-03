cmake_minimum_required(VERSION 3.22.1)
project(cppide_native LANGUAGES C CXX)

# Pinned, vendored libvterm 0.3.3. No configure-time network downloads.
set(LIBVTERM_DIR ${CMAKE_CURRENT_SOURCE_DIR}/libvterm)
add_library(vterm STATIC
    ${LIBVTERM_DIR}/src/encoding.c
    ${LIBVTERM_DIR}/src/keyboard.c
    ${LIBVTERM_DIR}/src/mouse.c
    ${LIBVTERM_DIR}/src/parser.c
    ${LIBVTERM_DIR}/src/pen.c
    ${LIBVTERM_DIR}/src/screen.c
    ${LIBVTERM_DIR}/src/state.c
    ${LIBVTERM_DIR}/src/unicode.c
    ${LIBVTERM_DIR}/src/vterm.c
)
target_include_directories(vterm PUBLIC ${LIBVTERM_DIR}/include PRIVATE ${LIBVTERM_DIR}/src)
target_compile_features(vterm PRIVATE c_std_99)
target_compile_options(vterm PRIVATE -Wall -Wextra -Wno-unused-parameter)

add_library(cppide_native SHARED native_bridge.cpp)
target_compile_features(cppide_native PRIVATE cxx_std_20)
target_compile_options(cppide_native PRIVATE -Wall -Wextra -Wpedantic)
find_library(android_log log)
target_link_libraries(cppide_native PRIVATE vterm ${android_log})
