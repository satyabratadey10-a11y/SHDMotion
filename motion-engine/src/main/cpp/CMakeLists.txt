cmake_minimum_required(VERSION 3.22.1)
project(motionengine LANGUAGES CXX)

add_library(motionengine SHARED motion-engine.cpp)
target_compile_features(motionengine PRIVATE cxx_std_17)
target_compile_options(motionengine PRIVATE -O3 -ffast-math)
target_link_libraries(motionengine PRIVATE log android jnigraphics)
