cmake_minimum_required(VERSION 3.22.1)

project(radiofmx_native LANGUAGES CXX)

add_library(
    radiofmx_native
    SHARED
    native_fm.cpp
)

target_compile_features(radiofmx_native PRIVATE cxx_std_17)

find_library(log-lib log)
find_library(dl-lib dl)

target_link_libraries(
    radiofmx_native
    ${log-lib}
    ${dl-lib}
)
