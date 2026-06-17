cmake_minimum_required(VERSION 3.9.0)
project(PersistentBackgroundLocation)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_VERBOSE_MAKEFILE ON)

# The shared library Nitrogen wires its generated C++ bindings + JNI onload into.
add_library(PersistentBackgroundLocation SHARED)

# Adds the generated sources, headers, and links fbjni / ReactAndroid / NitroModules.
include(${CMAKE_SOURCE_DIR}/../nitrogen/generated/android/PersistentBackgroundLocation+autolinking.cmake)
