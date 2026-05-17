cmake_minimum_required(VERSION 3.10.2)
project("speedhook")

# Speed hook library for injection
add_library(speedhook SHARED speedhook.c arm64_hook.c)

target_link_libraries(speedhook
    log
    dl
)

# Injector binary for ptrace-based injection
add_executable(speedinjector injector.c)

target_link_libraries(speedinjector
    log
    dl
)

target_link_options(speedinjector PRIVATE -pie -fPIE)

set_target_properties(speedhook speedinjector PROPERTIES
    CXX_STANDARD 11
    C_STANDARD 11
)
