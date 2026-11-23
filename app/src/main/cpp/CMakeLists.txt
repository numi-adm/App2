cmake_minimum_required(VERSION 3.18.1)
project("roblox_dash" C)

add_library(
    roblox_dash
    SHARED
    native_core.c
    bot_core.c
)

find_library(
    log-lib
    log
)

find_library(
    m-lib
    m
)

target_link_libraries(
    roblox_dash
    ${log-lib}
    ${m-lib}
)
