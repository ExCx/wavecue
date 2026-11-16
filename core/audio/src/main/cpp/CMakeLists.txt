cmake_minimum_required(VERSION 3.22.1)
project(wavecue_audio LANGUAGES CXX)

add_library(wavecue_audio SHARED native-lib.cpp)

target_compile_features(wavecue_audio PRIVATE cxx_std_17)
target_compile_options(wavecue_audio PRIVATE -Wall -Wextra -Wno-unused-parameter -fvisibility=hidden)

find_library(aaudio-lib aaudio)
find_library(log-lib log)

target_link_libraries(wavecue_audio
        ${aaudio-lib}
        ${log-lib}
        android)
