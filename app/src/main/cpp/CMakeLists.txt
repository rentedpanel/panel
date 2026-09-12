cmake_minimum_required(VERSION 3.18)
project("injector")

add_library(
    injector
    SHARED
    injector.cpp
)

add_executable(
    mem_daemon
    daemon.cpp
)

find_library(
    log-lib
    log
)

target_link_libraries(
    injector
    ${log-lib}
)

target_link_libraries(
    mem_daemon
    ${log-lib}
)
