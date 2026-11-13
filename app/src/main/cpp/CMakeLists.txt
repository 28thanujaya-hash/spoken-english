cmake_minimum_required(VERSION 3.22.1)
project(spokenvoice CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# misaki-g2p (words -> sounds), built without eSpeak (no GPL code).
add_library(misaki_g2p STATIC
    misaki/src/espeak.cpp misaki/src/g2p.cpp misaki/src/lexicon.cpp misaki/src/misaki-g2p.cpp
    misaki/src/numbers.cpp misaki/src/tagger.cpp misaki/src/tokenizer.cpp misaki/src/unicode.cpp)
target_include_directories(misaki_g2p PUBLIC misaki/include PRIVATE misaki/src)
target_compile_definitions(misaki_g2p PRIVATE MISAKI_G2P_BUILD)
set_target_properties(misaki_g2p PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_compile_options(misaki_g2p PRIVATE -O2)

# Our bridge. ONNX Runtime is opened at run time (dlopen), so only its headers are needed here.
add_library(spokenvoice SHARED voice_jni.cpp)
target_include_directories(spokenvoice PRIVATE ort)
target_link_libraries(spokenvoice PRIVATE misaki_g2p dl log)
target_compile_options(spokenvoice PRIVATE -O2)
target_link_options(spokenvoice PRIVATE -Wl,--gc-sections -s)
