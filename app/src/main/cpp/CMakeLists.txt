cmake_minimum_required(VERSION 3.22.1)
project("coloros_x11_bridge")

add_library(
    coloros_x11_bridge
    SHARED
    native_bridge.cpp
)

find_library(log-lib log)
find_library(android-lib android)
find_library(egl-lib EGL)
find_library(glesv3-lib GLESv3)

target_link_libraries(
    coloros_x11_bridge
    ${log-lib}
    ${android-lib}
    ${egl-lib}
    ${glesv3-lib}
)
