cmake_minimum_required(VERSION 3.18.1)
project("roblox_dash" C)

add_library(
    roblox_dash
    SHARED
    native_core.c
)

find_library(
    log-lib
    log
)

target_link_libraries(
    roblox_dash
    ${log-lib}
)
