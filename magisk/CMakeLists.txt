cmake_minimum_required(VERSION 3.22)
project(ble_m3_remapper CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
add_executable(ble-m3-remapper native/ble_m3_remapper.cpp)
target_compile_options(ble-m3-remapper PRIVATE -Wall -Wextra -Werror)
