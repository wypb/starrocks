#!/usr/bin/env bash
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

#################################################################################
# This script will
# 1. Check prerequisite libraries. Including:
#    cmake byacc flex automake libtool binutils-dev libiberty-dev bison
# 2. Compile and install all thirdparties which are downloaded
#    using *download-thirdparty.sh*.
#
# This script will run *download-thirdparty.sh* once again
# to check if all thirdparties have been downloaded, unpacked and patched.
#################################################################################
set -eo pipefail

curdir=`dirname "$0"`
curdir=`cd "$curdir"; pwd`

export STARROCKS_HOME=${STARROCKS_HOME:-$curdir/..}
export TP_DIR=$curdir

# include custom environment variables
if [[ -f ${STARROCKS_HOME}/env.sh ]]; then
    . ${STARROCKS_HOME}/env.sh
fi

if [[ ! -f ${TP_DIR}/download-thirdparty.sh ]]; then
    echo "Download thirdparty script is missing".
    exit 1
fi

if [ ! -f ${TP_DIR}/vars.sh ]; then
    echo "vars.sh is missing".
    exit 1
fi
. ${TP_DIR}/vars.sh

# Check args
usage() {
    echo "
Usage: $0 [options...] [packages...]

  Description:
    Build thirdparty dependencies for StarRocks. If no packages are specified,
    all packages will be built in the default order.

  Optional options:
    -j<num>                Build with <num> parallel jobs (can also use -j <num>)
    --clean                Clean extracted source before building
    --continue <package>   Continue building from specified package
    -h, --help             Show this help message

  Examples:
    # Build all packages with default parallelism
    $0

    # Build all packages with 8 parallel jobs
    $0 -j8

    # Clean and rebuild everything with 16 parallel jobs
    $0 --clean -j16

    # Continue building from rocksdb (useful after build failure)
    $0 --continue rocksdb

    # Build only specific packages (in dependency order)
    # Note: packages are built in the same order as full build to respect dependencies
    $0 openssl curl protobuf

    # Clean and build only specific packages
    $0 --clean boost thrift
  "
    exit 1
}

get_all_package_sources() {
    local archive
    for archive in $TP_ARCHIVES
    do
        local source_var="${archive}_SOURCE"
        if [[ -n "${!source_var}" ]]; then
            echo ${!source_var}
        fi
    done
}

# clean function
clean_sources() {
    if [[ ! -d "${TP_SOURCE_DIR}" ]]; then
        echo "Source directory ${TP_SOURCE_DIR} does not exist, nothing to clean."
        return
    fi

    echo "Cleaning extracted source directories..."

    # no packages specified, clean all sources
    if [[ ${#packages[@]} -eq 0 ]]; then
        local sources_to_clean
        sources_to_clean=$(get_all_package_sources)

        echo "$sources_to_clean" | while IFS= read -r source; do
            if [[ -n "$source" ]] && [[ -d "${TP_SOURCE_DIR}/${source}" ]]; then
                echo "Removing ${TP_SOURCE_DIR}/${source}"
                rm -rf "${TP_SOURCE_DIR}/${source}"
            fi
        done
    else
        # clean only specified sources
        for package in "${packages[@]}"; do
            # this converts package name to uppercase for matching
            local source_var_name="${package^^}_SOURCE"
            local source_dir="${!source_var_name}"

            if [[ -n "$source_dir" ]] && [[ -d "${TP_SOURCE_DIR}/${source_dir}" ]]; then
                echo "Removing ${TP_SOURCE_DIR}/${source_dir}"
                rm -rf "${TP_SOURCE_DIR}/${source_dir}"
            else
                echo "Warning: Cannot find source directory for package '${package}'"
            fi
        done
    fi

    echo "Clean completed!"
}

if ! OPTS="$(getopt \
    -n "$0" \
    -o 'hj:' \
    -l 'help,clean,continue:' \
    -- "$@")"; then
    usage
fi

eval set -- "${OPTS}"

KERNEL="$(uname -s)"

if [[ "${KERNEL}" == 'Darwin' ]]; then
    PARALLEL="$(($(sysctl -n hw.logicalcpu) / 4 + 1))"
else
    PARALLEL="$(($(nproc) / 4 + 1))"
fi

HELP=0
CLEAN=0
CONTINUE=0
start_package=""

while true; do
    case "$1" in
    -j)
        PARALLEL="$2"
        shift 2
        ;;
    -h)
        HELP=1
        shift
        ;;
    --help)
        HELP=1
        shift
        ;;
    --clean)
        CLEAN=1
        shift
        ;;
    --continue)
        CONTINUE=1
        start_package="${2}"
        shift 2
        ;;
    --)
        shift
        break
        ;;
    *)
        echo "Internal error"
        exit 1
        ;;
    esac
done

# checking for help first, before processing other arguments
if [[ "${HELP}" -eq 1 ]]; then
    usage
fi

packages=("$@")

if [[ "${CONTINUE}" -eq 1 ]]; then
    if [[ -z "${start_package}" ]] || [[ "${#}" -ne 0 ]]; then
        usage
    fi
fi

echo "Get params:
    PARALLEL            -- ${PARALLEL}
    CLEAN               -- ${CLEAN}
    PACKAGES            -- ${packages[*]}
    CONTINUE            -- ${start_package}
"

cd $TP_DIR

if [[ "${CLEAN}" -eq 1 ]]; then
    clean_sources
fi

# Download thirdparties.
${TP_DIR}/download-thirdparty.sh

# set COMPILER
if [[ ! -z ${STARROCKS_GCC_HOME} ]]; then
    export CC=${STARROCKS_GCC_HOME}/bin/gcc
    export CPP=${STARROCKS_GCC_HOME}/bin/cpp
    export CXX=${STARROCKS_GCC_HOME}/bin/g++
    export PATH=${STARROCKS_GCC_HOME}/bin:$PATH
else
    echo "STARROCKS_GCC_HOME environment variable is not set"
    exit 1
fi

# prepare installed prefix
mkdir -p ${TP_DIR}/installed

check_prerequest() {
    local CMD=$1
    local NAME=$2
    if ! $CMD; then
        echo $NAME is missing
        exit 1
    else
        echo $NAME is found
    fi
}

# sudo apt-get install cmake
# sudo yum install cmake
check_prerequest "${CMAKE_CMD} --version" "cmake"

# sudo apt-get install byacc
# sudo yum install byacc
check_prerequest "byacc -V" "byacc"

# sudo apt-get install flex
# sudo yum install flex
check_prerequest "flex -V" "flex"

# sudo apt-get install automake
# sudo yum install automake
check_prerequest "automake --version" "automake"

# sudo apt-get install libtool
# sudo yum install libtool
check_prerequest "libtoolize --version" "libtool"

BUILD_SYSTEM=${BUILD_SYSTEM:-make}

# sudo apt-get install binutils-dev
# sudo yum install binutils-devel
#check_prerequest "locate libbfd.a" "binutils-dev"

# sudo apt-get install libiberty-dev
# no need in centos 7.1
#check_prerequest "locate libiberty.a" "libiberty-dev"

# sudo apt-get install bison
# sudo yum install bison
#check_prerequest "bison --version" "bison"

#########################
# build all thirdparties
#########################


# Name of cmake build directory in each thirdpary project.
# Do not use `build`, because many projects contained a file named `BUILD`
# and if the filesystem is not case sensitive, `mkdir` will fail.
BUILD_DIR=starrocks_build
MACHINE_TYPE=$(uname -m)

# handle mac m1 platform, change arm64 to aarch64
if [[ "${MACHINE_TYPE}" == "arm64" ]]; then
    MACHINE_TYPE="aarch64"
fi

echo "machine type : $MACHINE_TYPE"

if [[ -z ${THIRD_PARTY_BUILD_WITH_AVX2} ]]; then
    THIRD_PARTY_BUILD_WITH_AVX2=ON
fi

if [ -e /proc/cpuinfo ] ; then
    # detect cpuinfo
    if [[ -z $(grep -o 'avx[^ ]\+' /proc/cpuinfo) ]]; then
        THIRD_PARTY_BUILD_WITH_AVX2=OFF
    fi
fi

check_if_source_exist() {
    if [ -z $1 ]; then
        echo "dir should specified to check if exist."
        exit 1
    fi

    if [ ! -d $TP_SOURCE_DIR/$1 ];then
        echo "$TP_SOURCE_DIR/$1 does not exist."
        exit 1
    fi
    echo "===== begin build $1"
}

check_if_archieve_exist() {
    if [ -z $1 ]; then
        echo "archieve should specified to check if exist."
        exit 1
    fi

    if [ ! -f $TP_SOURCE_DIR/$1 ];then
        echo "$TP_SOURCE_DIR/$1 does not exist."
        exit 1
    fi
}

# libevent
build_libevent() {
    check_if_source_exist $LIBEVENT_SOURCE
    cd $TP_SOURCE_DIR/$LIBEVENT_SOURCE
    if [ ! -f configure ]; then
        ./autogen.sh
    fi

    LDFLAGS="-L${TP_LIB_DIR}" \
    ./configure --prefix=$TP_INSTALL_DIR --enable-shared=no --disable-samples --disable-libevent-regress
    make -j$PARALLEL
    make install
}

build_openssl() {
    OPENSSL_PLATFORM="linux-x86_64"
    if [[ "${MACHINE_TYPE}" == "aarch64" ]]; then
        OPENSSL_PLATFORM="linux-aarch64"
    fi

    check_if_source_exist $OPENSSL_SOURCE
    cd $TP_SOURCE_DIR/$OPENSSL_SOURCE

    # use customized CFLAGS/CPPFLAGS/CXXFLAGS/LDFLAGS
    unset CXXFLAGS
    unset CPPFLAGS
    export CFLAGS="-O3 -fno-omit-frame-pointer -fPIC"

    LDFLAGS="-L${TP_LIB_DIR}" \
    LIBDIR="lib" \
    ./Configure --prefix=$TP_INSTALL_DIR -lz -no-shared ${OPENSSL_PLATFORM} --libdir=lib
    make -j$PARALLEL
    make install_sw

    restore_compile_flags
}

# thrift
build_thrift() {
    check_if_source_exist $THRIFT_SOURCE
    cd $TP_SOURCE_DIR/$THRIFT_SOURCE

    if [ ! -f configure ]; then
        ./bootstrap.sh
    fi

    echo ${TP_LIB_DIR}
    ./configure LDFLAGS="-L${TP_LIB_DIR} -static-libstdc++ -static-libgcc" LIBS="-lssl -lcrypto -ldl" \
    --prefix=$TP_INSTALL_DIR --docdir=$TP_INSTALL_DIR/doc --enable-static --disable-shared --disable-tests \
    --disable-tutorial --without-qt4 --without-qt5 --without-csharp --without-erlang --without-nodejs \
    --without-lua --without-perl --without-php --without-php_extension --without-dart --without-ruby \
    --without-haskell --without-go --without-haxe --without-d --without-python -without-java -without-rs --with-cpp \
    --with-libevent=$TP_INSTALL_DIR --with-boost=$TP_INSTALL_DIR --with-openssl=$TP_INSTALL_DIR

    if [ -f compiler/cpp/thrifty.hh ];then
        mv compiler/cpp/thrifty.hh compiler/cpp/thrifty.h
    fi

    make -j$PARALLEL
    make install
}

# llvm
build_llvm() {
    export CFLAGS="-O3 -fno-omit-frame-pointer -std=c99 -D_POSIX_C_SOURCE=200112L ${FILE_PREFIX_MAP_OPTION}"
    export CXXFLAGS="-O3 -fno-omit-frame-pointer -Wno-class-memaccess ${FILE_PREFIX_MAP_OPTION}"

    LLVM_TARGET="X86"
    if [[ "${MACHINE_TYPE}" == "aarch64" ]]; then
        LLVM_TARGET="AArch64"
    fi

    LLVM_TARGETS_TO_BUILD=(
        "LLVMBitstreamReader"
        "LLVMRuntimeDyld"
        "LLVMOption"
        "LLVMAsmPrinter"
        "LLVMProfileData"
        "LLVMAsmParser"
        "LLVMOrcTargetProcess"
        "LLVMExecutionEngine"
        "LLVMBinaryFormat"
        "LLVMDebugInfoDWARF"
        "LLVMObjCARCOpts"
        "LLVMPasses"
        "LLVMCodeGen"
        "LLVMFrontendOpenMP"
        "LLVMMCDisassembler"
        "LLVMSupport"
        "LLVMJITLink"
        "LLVMCFGuard"
        "LLVMInstrumentation"
        "LLVMInstCombine"
        "LLVMipo"
        "LLVMVectorize"
        "LLVMIRReader"
        "LLVMCore"
        "LLVMTarget"
        "LLVMMC"
        "LLVMAnalysis"
        "LLVMGlobalISel"
        "LLVMScalarOpts"
        "LLVMLinker"
        "LLVMCoroutines"
        "LLVMTargetParser"
        "LLVMDemangle"
        "LLVMRemarks"
        "LLVMDebugInfoCodeView"
        "LLVMAggressiveInstCombine"
        "LLVMIRPrinter"
        "LLVMOrcShared"
        "LLVMOrcJIT"
        "LLVMTextAPI"
        "LLVMBitWriter"
        "LLVMBitReader"
        "LLVMObject"
        "LLVMTransformUtils"
        "LLVMSelectionDAG"
        "LLVMMCParser"
        "LLVMSupport"
    )
    if [ "${LLVM_TARGET}" == "X86" ]; then
        LLVM_TARGETS_TO_BUILD+=("LLVMX86Info" "LLVMX86Desc" "LLVMX86CodeGen" "LLVMX86AsmParser" "LLVMX86Disassembler")
    elif [ "${LLVM_TARGET}" == "AArch64" ]; then
        LLVM_TARGETS_TO_BUILD+=("LLVMAArch64Info" "LLVMAArch64Desc" "LLVMAArch64CodeGen" "LLVMAArch64Utils" "LLVMAArch64AsmParser" "LLVMAArch64Disassembler")
    fi

    LLVM_TARGETS_TO_INSTALL=()
    for target in ${LLVM_TARGETS_TO_BUILD[@]}; do
        LLVM_TARGETS_TO_INSTALL+=("install-${target}")
    done

    check_if_source_exist $LLVM_SOURCE

    cd ${TP_SOURCE_DIR}/${LLVM_SOURCE}
    mkdir -p llvm-build
    cd llvm-build
    rm -rf CMakeCache.txt CMakeFiles/

    LDFLAGS="-L${TP_LIB_DIR} -static-libstdc++ -static-libgcc" \
    $CMAKE_CMD -S ../llvm -G "${CMAKE_GENERATOR}" \
    -DLLVM_ENABLE_EH:Bool=True \
    -DLLVM_ENABLE_RTTI:Bool=True \
    -DLLVM_ENABLE_PIC:Bool=True \
    -DLLVM_ENABLE_TERMINFO:Bool=False \
    -DLLVM_TARGETS_TO_BUILD=${LLVM_TARGET} \
    -DLLVM_BUILD_LLVM_DYLIB:BOOL=False \
    -DLLVM_INCLUDE_TOOLS:BOOL=False \
    -DLLVM_BUILD_TOOLS:BOOL=False \
    -DLLVM_INCLUDE_EXAMPLES:BOOL=False \
    -DLLVM_INCLUDE_TESTS:BOOL=False \
    -DLLVM_INCLUDE_BENCHMARKS:BOOL=False \
    -DBUILD_SHARED_LIBS:BOOL=False \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX=${TP_INSTALL_DIR}/llvm ../llvm-build

    # TODO(yueyang): Add more targets.
    # This is a little bit hack, we need to minimize the build time and binary size.
    REQUIRES_RTTI=1 ${BUILD_SYSTEM} -j$PARALLEL ${LLVM_TARGETS_TO_BUILD[@]}
    ${BUILD_SYSTEM} install-llvm-headers
    ${BUILD_SYSTEM} ${LLVM_TARGETS_TO_INSTALL[@]}

    restore_compile_flags
}
# protobuf
build_protobuf() {
    check_if_source_exist $PROTOBUF_SOURCE
    cd $TP_SOURCE_DIR/$PROTOBUF_SOURCE
    rm -fr gmock
    mkdir gmock
    cd gmock
    tar xf ${TP_SOURCE_DIR}/$GTEST_NAME
    mv $GTEST_SOURCE gtest
    cd $TP_SOURCE_DIR/$PROTOBUF_SOURCE
    ./autogen.sh
    LDFLAGS="-L${TP_LIB_DIR} -static-libstdc++ -static-libgcc -pthread -Wl,--whole-archive -lpthread -Wl,--no-whole-archive" \
    ./configure --prefix=${TP_INSTALL_DIR} --disable-shared --enable-static --with-zlib --with-zlib-include=${TP_INSTALL_DIR}/include
    make -j$PARALLEL
    make install
}

# gflags
build_gflags() {
    check_if_source_exist $GFLAGS_SOURCE

    cd $TP_SOURCE_DIR/$GFLAGS_SOURCE
    mkdir -p $BUILD_DIR
    cd $BUILD_DIR
    rm -rf CMakeCache.txt CMakeFiles/
    $CMAKE_CMD -G "${CMAKE_GENERATOR}" -DCMAKE_INSTALL_PREFIX=$TP_INSTALL_DIR \
    -DCMAKE_POSITION_INDEPENDENT_CODE=On ../
    ${BUILD_SYSTEM} -j$PARALLEL
    ${BUILD_SYSTEM} install
}

# glog
build_glog() {
    check_if_source_exist $GLOG_SOURCE
    cd $TP_SOURCE_DIR/$GLOG_SOURCE

    $CMAKE_CMD -G "${CMAKE_GENERATOR}" -DCMAKE_INSTALL_PREFIX=$TP_INSTALL_DIR -DBUILD_SHARED_LIBS=OFF -DCMAKE_POSITION_INDEPENDENT_CODE=ON -DCMAKE_INSTALL_LIBDIR=lib

    ${BUILD_SYSTEM} -j$PARALLEL
    ${BUILD_SYSTEM} install
}

# gtest
build_gtest() {
    check_if_source_exist $GTEST_SOURCE

    cd $TP_SOURCE_DIR/$GTEST_SOURCE
    mkdir -p $BUILD_DIR
    cd $BUILD_DIR
    rm -rf CMakeCache.txt CMakeFiles/
    $CMAKE_CMD -G "${CMAKE_GENERATOR}" -DCMAKE_INSTALL_PREFIX=$TP_INSTALL_DIR -DCMAKE_INSTALL_LIBDIR=lib \
    -DCMAKE_POSITION_INDEPENDENT_CODE=On ../
    ${BUILD_SYSTEM} -j$PARALLEL
    ${BUILD_SYSTEM} install
}

# rapidjson
build_rapidjson() {
    check_if_source_exist $RAPIDJSON_SOURCE

    rm -rf $TP_INSTALL_DIR/rapidjson
    cp -r $TP_SOURCE_DIR/$RAPIDJSON_SOURCE/include/rapidjson $TP_INCLUDE_DIR/
}

# simdjson
build_simdjson() {
    check_if_source_exist $SIMDJSON_SOURCE
    cd $TP_SOURCE_DIR/$SIMDJSON_SOURCE

    #ref: https://github.com/simdjson/simdjson/blob/master/HACKING.md
    mkdir -p $BUILD_DIR
    cd $BUILD_DIR
    $CMAKE_CMD -G "${CMAKE_GENERATOR}" -DCMAKE_CXX_FLAGS="-O3 -fPIC" -DCMAKE_C_FLAGS="-O3 -fPIC" -DCMAKE_POSITION_INDEPENDENT_CODE=True -DSIMDJSON_AVX512_ALLOWED=OFF ..
    $CMAKE_CMD --build .
    mkdir -p $TP_INSTALL_DIR/lib

    cp $TP_SOURCE_DIR/$SIMDJSON_SOURCE/$BUILD_DIR/libsimdjson.a $TP_INSTALL_DIR/lib
    cp -r $TP_SOURCE_DIR/$SIMDJSON_SOURCE/include/* $TP_INCLUDE_DIR/
}

# poco
build_poco() {
  check_if_source_exist $POCO_SOURCE
  cd $TP_SOURCE_DIR/$POCO_SOURCE

  mkdir -p $BUILD_DIR
  cd $BUILD_DIR
  rm -rf CMakeCache.txt CMakeFiles/
  $CMAKE_CMD .. -DBUILD_SHARED_LIBS=NO -DOPENSSL_ROOT_DIR=$TP_INSTALL_DIR -DCMAKE_INSTALL_PREFIX=$TP_INSTALL_DIR \
   -DENABLE_XML=OFF -DENABLE_JSON=OFF -DENABLE_NET=ON -DENABLE_NETSSL=ON -DENABLE_CRYPTO=OFF -DENABLE_JWT=OFF -DENABLE_DATA=OFF -DENABLE_DATA_SQLITE=OFF -DENABLE_DATA_MYSQL=OFF -DENABLE_DATA_POSTGRESQL=OFF -DENABLE_DATA_ODBC=OFF \
   -DENABLE_MONGODB=OFF -DENABLE_REDIS=OFF -DENABLE_UTIL=OFF -DENABLE_ZIP=OFF -DENABLE_APACHECONNECTOR=OFF -DENABLE_ENCODINGS=OFF \
   -DENABLE_PAGECOMPILER=OFF -DENABLE_PAGECOMPILER_FILE2PAGE=OFF -DENABLE_ACTIVERECORD=OFF -DENABLE_ACTIVERECORD_COMPILER=OFF -DENABLE_PROMETHEUS=OFF
  $CMAKE_CMD --build . --config Release --target install
}

# snappy
build_snappy() {
    check_if_source_exist $SNAPPY_SOURCE
    cd $TP_SOURCE_DIR/$SNAPPY_SOURCE

    mkdir -p $BUILD_DIR
    cd $BUILD_DIR
    rm -rf CMakeCache.txt CMakeFiles/
    $CMAKE_CMD -DCMAKE_INSTALL_PREFIX=$TP_INSTALL_DIR \
    -G "${CMAKE_GENERATOR}" \
    -DCMAKE_INSTALL_LIBDIR=lib64 \
    -DCMAKE_POSITION_INDEPENDENT_CODE=On \
    -DCMAKE_INSTALL_INCLUDEDIR=$TP_INCLUDE_DIR/snappy \
    -DSNAPPY_BUILD_TESTS=0 ../
    ${BUILD_SYSTEM} -j$PARALLEL
    ${BUILD_SYSTEM} install
    if [ -f $TP_INSTALL_DIR/lib64/libsnappy.a ]; then
        mkdir -p $TP_INSTALL_DIR/lib
        cp $TP_INSTALL_DIR/lib64/libsnappy.a $TP_INSTALL_DIR/lib/libsnappy.a
    fi

    #build for libarrow.a
    cp $TP_INCLUDE_DIR/snappy/snappy-c.h  $TP_INCLUDE_DIR/snappy-c.h
    cp $TP_INCLUDE_DIR/snappy/snappy-sinksource.h  $TP_INCLUDE_DIR/snappy-sinksource.h
    cp $TP_INCLUDE_DIR/snappy/snappy-stubs-public.h  $TP_INCLUDE_DIR/snappy-stubs-public.h
    cp $TP_INCLUDE_DIR/snappy/snappy.h  $TP_INCLUDE_DIR/snappy.h
    cp $TP_INSTALL_DIR/lib/libsnappy.a $TP_INSTALL_DIR/libsnappy.a
}

# gperftools
build_gperftools() {
    check_if_source_exist $GPERFTOOLS_SOURCE
    cd $TP_SOURCE_DIR/$GPERFTOOLS_SOURCE

    if [ ! -f configure ]; then
        ./autogen.sh
    fi

    LDFLAGS="-L${TP_LIB_DIR}" \
    CFLAGS="-O3 -fno-omit-frame-pointer -fPIC -g" \
    ./configure --prefix=$TP_INSTALL_DIR/gperftools --disable-shared --enable-static --disable-libunwind --with-pic --enable-frame-pointers
    make -j$PARALLEL
    make install
}

# zlib
build_zlib() {
    check_if_source_exist $ZLIB_SOURCE
    cd $TP_SOURCE_DIR/$ZLIB_SOURCE

    LDFLAGS="-L${TP_LIB_DIR}" \
    ./configure --prefix=$TP_INSTALL_DIR --static
    make -j$PARALLEL
    make install

    # build minizip
    cd $TP_SOURCE_DIR/$ZLIB_SOURCE/contrib/minizip
    autoreconf --force --install
    ./configure --prefix=$TP_INSTALL_DIR --enable-static=yes --enable-shared=no
    make -j$PARALLEL
    make install
}

# lz4
build_lz4() {
    check_if_source_exist $LZ4_SOURCE
    cd $TP_SOURCE_DIR/$LZ4_SOURCE

    make -C lib -j$PARALLEL install PREFIX=$TP_INSTALL_DIR \
    INCLUDEDIR=$TP_INCLUDE_DIR/lz4/ BUILD_SHARED=no
}

# lzo
build_lzo2() {
    check_if_source_exist $LZO2_SOURCE
    cd $TP_SOURCE_DIR/$LZO2_SOURCE

    CPPFLAGS="-I${TP_INCLUDE_DIR}" \
        LDFLAGS="-L${TP_LIB_DIR}" \
        ./configure --prefix="${TP_INSTALL_DIR}" --disable-shared --enable-static

    make -j "${PARALLEL}"
    make install
}

# bzip
build_bzip() {
    check_if_source_exist $BZIP_SOURCE
    cd $TP_SOURCE_DIR/$BZIP_SOURCE
    make -j$PARALLEL install PREFIX=$TP_INSTALL_DIR
}

# curl
build_curl() {
    check_if_source_exist $CURL_SOURCE
    cd $TP_SOURCE_DIR/$CURL_SOURCE

    LDFLAGS="-L${TP_LIB_DIR}" LIBS="-lssl -lcrypto -ldl" \
    ./configure --prefix=$TP_INSTALL_DIR --disable-shared --enable-static \
    --without-librtmp --with-ssl=${TP_INSTALL_DIR} --without-libidn2 --without-libgsasl --disable-ldap --enable-ipv6 --without-brotli
    make -j$PARALLEL
    make install
}

# re2
build_re2() {
    check_if_source_exist $RE2_SOURCE
    cd $TP_SOURCE_DIR/$RE2_SOURCE

    $CMAKE_CMD -G "${CMAKE_GENERATOR}" -DCMAKE_BUILD_TYPE=Release \
	    -DBUILD_SHARED_LIBS=0 -DCMAKE_INSTALL_PREFIX=$TP_INSTALL_DIR -DCMAKE_INSTALL_LIBDIR=lib
    ${BUILD_SYSTEM} -j$PARALLEL install
}

# boost
build_boost() {
    check_if_source_exist $BOOST_SOURCE
    cd $TP_SOURCE_DIR/$BOOST_SOURCE

    # It is difficult to generate static linked b2, so we use LD_LIBRARY_PATH instead
    ./bootstrap.sh --prefix=$TP_INSTALL_DIR
    LD_LIBRARY_PATH=${STARROCKS_GCC_HOME}/lib:${STARROCKS_GCC_HOME}/lib64:${LD_LIBRARY_PATH} \
    ./b2 link=static runtime-link=static -j $PARALLEL --without-test --without-mpi --without-graph --without-graph_parallel --without-python cxxflags="-std=c++11 -g -fPIC -I$TP_INCLUDE_DIR -L$TP_LIB_DIR ${FILE_PREFIX_MAP_OPTION}" install
}

#leveldb
build_leveldb() {
    check_if_source_exist $LEVELDB_SOURCE
    cd $TP_SOURCE_DIR/$LEVELDB_SOURCE
    LDFLAGS="-L ${TP_LIB_DIR} -static-libstdc++ -static-libgcc" \
    make -j$PARALLEL
    cp out-static/libleveldb.a $TP_LIB_DIR/libleveldb.a
    cp -r include/leveldb $TP_INCLUDE_DIR
}

# brpc
build_brpc() {
    check_if_source_exist $BRPC_SOURCE

    cd $TP_SOURCE_DIR/$BRPC_SOURCE
    CMAKE_GENERATOR="Unix Makefiles"
    BUILD_SYSTEM='make'
    PATH=$PATH:$TP_INSTALL_DIR/bin/ ./config_brpc.sh --headers="$TP_INSTALL_DIR/include" --libs="$TP_INSTALL_DIR/bin $TP_INSTALL_DIR/lib" --with-glog --with-thrift
    make -j$PARALLEL
    cp -rf output/* ${TP_INSTALL_DIR}/
    if [ -f $TP_INSTALL_DIR/lib/libbrpc.a ]; then
        mkdir -p $TP_INSTALL_DIR/lib64
        cp $TP_SOURCE_DIR/$BRPC_SOURCE/output/lib/libbrpc.a $TP_INSTALL_DIR/lib64/
    fi
}

# rocksdb
build_rocksdb() {
    check_if_source_exist $ROCKSDB_SOURCE

    cd $TP_SOURCE_DIR/$ROCKSDB_SOURCE
    make clean

    CFLAGS= \
    EXTRA_CFLAGS="-I ${TP_INCLUDE_DIR} -I ${TP_INCLUDE_DIR}/snappy -I ${TP_INCLUDE_DIR}/lz4 -L${TP_LIB_DIR} ${FILE_PREFIX_MAP_OPTION}" \
    EXTRA_CXXFLAGS="-fPIC -Wno-redundant-move -Wno-deprecated-copy -Wno-stringop-truncation -Wno-pessimizing-move -I ${TP_INCLUDE_DIR} -I ${TP_INCLUDE_DIR}/snappy ${FILE_PREFIX_MAP_OPTION}" \
    EXTRA_LDFLAGS="-static-libstdc++ -static-libgcc" \
    PORTABLE=1 make USE_RTTI=1 -j$PARALLEL static_lib

    cp librocksdb.a $TP_LIB_DIR/librocksdb.a
    cp -r include/rocksdb $TP_INCLUDE_DIR
}

# kerberos
build_kerberos() {
    check_if_source_exist $KRB5_SOURCE
    cd $TP_SOURCE_DIR/$KRB5_SOURCE/src
    CFLAGS="-std=gnu17 -fcommon -fPIC ${FILE_PREFIX_MAP_OPTION}" LDFLAGS="-L$TP_INSTALL_DIR/lib -pthread -ldl" \
    ./configure --prefix=$TP_INSTALL_DIR --enable-static --disable-shared --with-spake-openssl=$TP_INSTALL_DIR
    make -j$PARALLEL
    make install
}

# sasl
build_sasl() {
    check_if_source_exist $SASL_SOURCE
    cd $TP_SOURCE_DIR/$SASL_SOURCE
    CFLAGS="-fPIC" LDFLAGS="-L$TP_INSTALL_DIR/lib -lresolv -pthread -ldl" ./autogen.sh --prefix=$TP_INSTALL_DIR --enable-gssapi=yes --enable-static --disable-shared --with-openssl=$TP_INSTALL_DIR --with-gss_impl=mit
    make -j$PARALLEL
    make install
}

# librdkafka
build_librdkafka() {
    check_if_source_exist $LIBRDKAFKA_SOURCE

    cd $TP_SOURCE_DIR/$LIBRDKAFKA_SOURCE

    mkdir -p sr_build && cd sr_build
    $CMAKE_CMD -DCMAKE_LIBRARY_PATH="$TP_INSTALL_DIR/lib;$TP_INSTALL_DIR/lib64" \
        -DCMAKE_INCLUDE_PATH="$TP_INSTALL_DIR/include;$TP_INSTALL_DIR/include/zstd;$TP_INSTALL_DIR/include/lz4" \
        -DBUILD_SHARED_LIBS=0 -DCMAKE_INSTALL_PREFIX=$TP_INSTALL_DIR -DRDKAFKA_BUILD_STATIC=ON -DWITH_SASL=ON -DWITH_SASL_SCRAM=ON \
        -DRDKAFKA_BUILD_EXAMPLES=OFF -DRDKAFKA_BUILD_TESTS=OFF -DWITH_SSL=ON -DWITH_ZSTD=ON -DCMAKE_INSTALL_LIBDIR=lib ..

    ${BUILD_SYSTEM} -j$PARALLEL
    ${BUILD_SYSTEM} install
}

# pulsar
build_pulsar() {
    check_if_source_exist $PULSAR_SOURCE

    cd $TP_SOURCE_DIR/$PULSAR_SOURCE

    $CMAKE_CMD -DCMAKE_LIBRARY_PATH=$TP_INSTALL_DIR/lib -DCMAKE_INCLUDE_PATH=$TP_INSTALL_DIR/include \
        -DPROTOC_PATH=$TP_INSTALL_DIR/bin/protoc -DOPENSSL_ROOT_DIR=$TP_INSTALL_DIR -DBUILD_TESTS=OFF -DBUILD_PYTHON_WRAPPER=OFF -DBUILD_DYNAMIC_LIB=OFF .
    ${BUILD_SYSTEM} -j$PARALLEL

    cp lib/libpulsar.a $TP_INSTALL_DIR/lib/
    cp -r include/pulsar $TP_INSTALL_DIR/include/
}

# flatbuffers
build_flatbuffers() {
  check_if_source_exist $FLATBUFFERS_SOURCE
  cd $TP_SOURCE_DIR/$FLATBUFFERS_SOURCE
  mkdir -p $BUILD_DIR
  cd $BUILD_DIR
  rm -rf CMakeCache.txt CMakeFiles/

  LDFLAGS="-static-libstdc++ -static-libgcc" \
  ${CMAKE_CMD} .. -G "${CMAKE_GENERATOR}" -DFLATBUFFERS_BUILD_TESTS=OFF
  ${BUILD_SYSTEM} -j$PARALLEL
  cp flatc  $TP_INSTALL_DIR/bin/flatc
  cp -r ../include/flatbuffers  $TP_INCLUDE_DIR/flatbuffers
  cp libflatbuffers.a $TP_LIB_DIR/libflatbuffers.a
}

build_brotli() {
    check_if_source_exist $BROTLI_SOURCE
    cd $TP_SOURCE_DIR/$BROTLI_SOURCE
    mkdir -p $BUILD_DIR
    cd $BUILD_DIR
    ${CMAKE_CMD} .. -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX=$TP_INSTALL_DIR -DCMAKE_INSTALL_LIBDIR=lib64
    ${BUILD_SYSTEM} -j$PARALLEL
    ${BUILD_SYSTEM} install
    mv -f $TP_INSTALL_DIR/lib64/libbrotlienc-static.a $TP_INSTALL_DIR/lib64/libbrotlienc.a
    mv -f $TP_INSTALL_DIR/lib64/libbrotlidec-static.a $TP_INSTALL_DIR/lib64/libbrotlidec.a
    mv -f $TP_INSTALL_DIR/lib64/libbrotlicommon-static.a $TP_INSTALL_DIR/lib64/libbrotlicommon.a
    rm $TP_INSTALL_DIR/lib64/libbrotli*.so
    rm $TP_INSTALL_DIR/lib64/libbrotli*.so.*
}

# arrow
build_arrow() {
    export CXXFLAGS="-O3 -fno-omit-frame-pointer -fPIC -g ${FILE_PREFIX_MAP_OPTION}"
    export CFLAGS="-O3 -fno-omit-frame-pointer -fPIC -g ${FILE_PREFIX_MAP_OPTION}"
    export CPPFLAGS=$CXXFLAGS

    check_if_source_exist $ARROW_SOURCE
    cd $TP_SOURCE_DIR/$ARROW_SOURCE/cpp
    mkdir -p release
    cd release
    rm -rf CMakeCache.txt CMakeFiles/
    export ARROW_BROTLI_URL=${TP_SOURCE_DIR}/${BROTLI_NAME}
    export ARROW_GLOG_URL=${TP_SOURCE_DIR}/${GLOG_NAME}
    export ARROW_LZ4_URL=${TP_SOURCE_DIR}/${LZ4_NAME}
    export ARROW_SNAPPY_URL=${TP_SOURCE_DIR}/${SNAPPY_NAME}
    export ARROW_ZLIB_URL=${TP_SOURCE_DIR}/${ZLIB_NAME}
    export ARROW_FLATBUFFERS_URL=${TP_SOURCE_DIR}/${FLATBUFFERS_NAME}
    export ARROW_ZSTD_URL=${TP_SOURCE_DIR}/${ZSTD_NAME}
    export LDFLAGS="-L${TP_LIB_DIR} -static-libstdc++ -static-libgcc"

    # https://github.com/apache/arrow/blob/apache-arrow-5.0.0/cpp/src/arrow/memory_pool.cc#L286
    #
    # JemallocAllocator use mallocx and rallocx to allocate new memory, but mallocx and rallocx are Non-standard APIs,
    # and can not be hooked in BE, the memory used by arrow can not be counted by BE,
    # so disable jemalloc here and use SystemAllocator.
    #
    # Currently, the standard APIs are hooked in BE, so the jemalloc standard APIs will actually be used.
    ${CMAKE_CMD} -DARROW_PARQUET=ON -DARROW_JSON=ON -DARROW_IPC=ON -DARROW_USE_GLOG=OFF -DARROW_BUILD_STATIC=ON -DARROW_BUILD_SHARED=OFF \
    -DARROW_WITH_BROTLI=ON -DARROW_WITH_LZ4=ON -DARROW_WITH_SNAPPY=ON -DARROW_WITH_ZLIB=ON -DARROW_WITH_ZSTD=ON \
    -DARROW_WITH_UTF8PROC=OFF -DARROW_WITH_RE2=OFF \
    -DARROW_JEMALLOC=OFF -DARROW_MIMALLOC=OFF \
    -DARROW_SIMD_LEVEL=AVX2 \
    -DARROW_RUNTIME_SIMD_LEVEL=AVX2 \
    -DCMAKE_INSTALL_PREFIX=$TP_INSTALL_DIR \
    -DCMAKE_INSTALL_LIBDIR=lib64 \
    -DARROW_GFLAGS_USE_SHARED=OFF \
    -DJEMALLOC_HOME=$TP_INSTALL_DIR/jemalloc \
    -Dzstd_SOURCE=BUNDLED \
    -DRapidJSON_ROOT=$TP_INSTALL_DIR \
    -DARROW_SNAPPY_USE_SHARED=OFF \
    -DZLIB_ROOT=$TP_INSTALL_DIR \
    -DLZ4_INCLUDE_DIR=$TP_INSTALL_DIR/include/lz4 \
    -DARROW_LZ4_USE_SHARED=OFF \
    -DBROTLI_ROOT=$TP_INSTALL_DIR \
    -DARROW_BROTLI_USE_SHARED=OFF \
    -Dgflags_ROOT=$TP_INSTALL_DIR/ \
    -DSnappy_ROOT=$TP_INSTALL_DIR/ \
    -DGLOG_ROOT=$TP_INSTALL_DIR/ \
    -DLZ4_ROOT=$TP_INSTALL_DIR/ \
    -DBoost_DIR=$TP_INSTALL_DIR \
    -DBoost_ROOT=$TP_INSTALL_DIR \
    -DARROW_BOOST_USE_SHARED=OFF \
    -DBoost_NO_BOOST_CMAKE=ON \
    -DARROW_FLIGHT=ON \
    -DARROW_FLIGHT_SQL=ON \
    -DCMAKE_PREFIX_PATH=${TP_INSTALL_DIR} \
    -G "${CMAKE_GENERATOR}" \
    -DThrift_ROOT=$TP_INSTALL_DIR/ ..

    ${BUILD_SYSTEM} -j$PARALLEL
    ${BUILD_SYSTEM} install

    if [ -f ./zstd_ep-install/lib64/libzstd.a ]; then
        cp -rf ./zstd_ep-install/lib64/libzstd.a $TP_INSTALL_DIR/lib64/libzstd.a
    else
        cp -rf ./zstd_ep-install/lib/libzstd.a $TP_INSTALL_DIR/lib64/libzstd.a
    fi
    # copy zstd headers
    mkdir -p ${TP_INSTALL_DIR}/include/zstd
    cp ./zstd_ep-install/include/* ${TP_INSTALL_DIR}/include/zstd

    restore_compile_flags
}

# s2
build_s2() {
    check_if_source_exist $S2_SOURCE
    cd $TP_SOURCE_DIR/$S2_SOURCE
    mkdir -p $BUILD_DIR
    cd $BUILD_DIR
    rm -rf CMakeCache.txt CMakeFiles/
    LDFLAGS="-L${TP_LIB_DIR} -static-libstdc++ -static-libgcc" \
    $CMAKE_CMD -G "${CMAKE_GENERATOR}" -DBUILD_SHARED_LIBS=0 -DCMAKE_INSTALL_PREFIX=$TP_INSTALL_DIR \
    -DCMAKE_INCLUDE_PATH="$TP_INSTALL_DIR/include" \
    -DBUILD_SHARED_LIBS=OFF \
    -DCMAKE_CXX_STANDARD="17" \
    -DGFLAGS_ROOT_DIR="$TP_INSTALL_DIR/include" \
    -DWITH_GFLAGS=ON \
    -DGLOG_ROOT_DIR="$TP_INSTALL_DIR/include" \
    -DWITH_GLOG=ON \
    -DCMAKE_LIBRARY_PATH="$TP_INSTALL_DIR/lib;$TP_INSTALL_DIR/lib64" ..
    ${BUILD_SYSTEM} -j$PARALLEL
    ${BUILD_SYSTEM} install
}

# bitshuffle
build_bitshuffle() {
    check_if_source_exist $BITSHUFFLE_SOURCE
    cd $TP_SOURCE_DIR/$BITSHUFFLE_SOURCE
    PREFIX=$TP_INSTALL_DIR

    # This library has significant optimizations when built with -mavx2. However,
    # we still need to support non-AVX2-capable hardware. So, we build it twice,
    # once with the flag and once without, and use some linker tricks to
    # suffix the AVX2 symbols with '_avx2'.
    arches="default avx2 avx512"
    # Becuase aarch64 don't support avx2, disable it.
    if [[ "${MACHINE_TYPE}" == "aarch64" ]]; then
        arches="default neon"
    fi

    to_link=""
    for arch in $arches ; do
        arch_flag=""
        if [ "$arch" == "avx2" ]; then
            arch_flag="-mavx2"
        elif [ "$arch" == "avx512" ]; then
            arch_flag="-march=icelake-server"
        elif [ "$arch" == "neon" ]; then
            arch_flag="-march=armv8-a+crc"
        fi
        tmp_obj=bitshuffle_${arch}_tmp.o
        dst_obj=bitshuffle_${arch}.o
        ${CC:-gcc} $EXTRA_CFLAGS $arch_flag -std=c99 -I$PREFIX/include/lz4/ -O3 -DNDEBUG -fPIC -c \
            "src/bitshuffle_core.c" \
            "src/bitshuffle.c" \
            "src/iochain.c"
        # Merge the object files together to produce a combined .o file.
        ld -r -o $tmp_obj bitshuffle_core.o bitshuffle.o iochain.o
        # For the AVX2 symbols, suffix them.
        if [[ "$arch" == "avx2" || "$arch" == "avx512" || "$arch" == "neon" ]]; then
            # Create a mapping file with '<old_sym> <suffixed_sym>' on each line.
            nm --defined-only --extern-only $tmp_obj | while read addr type sym ; do
              echo ${sym} ${sym}_${arch}
            done > renames.txt
            objcopy --redefine-syms=renames.txt $tmp_obj $dst_obj
        else
            mv $tmp_obj $dst_obj
        fi
        to_link="$to_link $dst_obj"
    done
    rm -f libbitshuffle.a
    ar rs libbitshuffle.a $to_link
    mkdir -p $PREFIX/include/bitshuffle
    cp libbitshuffle.a $PREFIX/lib/
    cp $TP_SOURCE_DIR/$BITSHUFFLE_SOURCE/src/bitshuffle.h $PREFIX/include/bitshuffle/bitshuffle.h
    cp $TP_SOURCE_DIR/$BITSHUFFLE_SOURCE/src/bitshuffle_core.h $PREFIX/include/bitshuffle/bitshuffle_core.h
}

# croaring bitmap
# If open AVX512 default, current version will be compiled failed on some machine, so close AVX512 default,
# When this problem is solved, a switch will be added to control.
build_croaringbitmap() {
    FORCE_AVX=ON
    # avx2 is not supported by aarch64.
    if [[ "${MACHINE_TYPE}" == "aarch64" ]]; then
        FORCE_AVX=FALSE
    fi
    if [[ `cat /proc/cpuinfo |grep avx|wc -l` == "0" ]]; then
        FORCE_AVX=FALSE
    fi
    check_if_source_exist $CROARINGBITMAP_SOURCE
    cd $TP_SOURCE_DIR/$CROARINGBITMAP_SOURCE
    mkdir -p $BUILD_DIR
    cd $BUILD_DIR
    rm -rf CMakeCache.txt CMakeFiles/
    LDFLAGS="-L${TP_LIB_DIR} -static-libstdc++ -static-libgcc" \
    $CMAKE_CMD -G "${CMAKE_GENERATOR}" -DROARING_BUILD_STATIC=ON -DCMAKE_INSTALL_PREFIX=$TP_INSTALL_DIR \
    -DCMAKE_INCLUDE_PATH="$TP_INSTALL_DIR/include" \
    -DENABLE_ROARING_TESTS=OFF \
    -DROARING_DISABLE_NATIVE=ON \
    -DFORCE_AVX=$FORCE_AVX \
    -DROARING_DISABLE_AVX512=ON \
    -DCMAKE_INSTALL_LIBDIR=lib \
    -DCMAKE_LIBRARY_PATH="$TP_INSTALL_DIR/lib;$TP_INSTALL_DIR/lib64" ..
    ${BUILD_SYSTEM} -j$PARALLEL
    ${BUILD_SYSTEM} install
}
#orc
build_orc() {
    check_if_source_exist $ORC_SOURCE
    cd $TP_SOURCE_DIR/$ORC_SOURCE
    mkdir -p $BUILD_DIR
    cd $BUILD_DIR
    rm -rf CMakeCache.txt CMakeFiles/
    $CMAKE_CMD ../ -DBUILD_JAVA=OFF \
    -G "${CMAKE_GENERATOR}" \
    -DPROTOBUF_HOME=$TP_INSTALL_DIR \
    -DSNAPPY_HOME=$TP_INSTALL_DIR \
    -DGTEST_HOME=$TP_INSTALL_DIR \
    -DLZ4_HOME=$TP_INSTALL_DIR \
    -DLZ4_INCLUDE_DIR=$TP_INSTALL_DIR/include/lz4 \
    -DZLIB_HOME=$TP_INSTALL_DIR\
    -DBUILD_LIBHDFSPP=OFF \
    -DBUILD_CPP_TESTS=OFF \
    -DCMAKE_INSTALL_PREFIX=$TP_INSTALL_DIR

    ${BUILD_SYSTEM} -j$PARALLEL
    ${BUILD_SYSTEM} install
}

#cctz
build_cctz() {
    check_if_source_exist $CCTZ_SOURCE
    cd $TP_SOURCE_DIR/$CCTZ_SOURCE

    make -j$PARALLEL
    PREFIX=${TP_INSTALL_DIR} make install
}

#fmt
build_fmt() {
    check_if_source_exist $FMT_SOURCE
    cd $TP_SOURCE_DIR/$FMT_SOURCE
    mkdir -p build
    cd build
    $CMAKE_CMD -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX=${TP_INSTALL_DIR} ../ \
            -DCMAKE_INSTALL_LIBDIR=lib64 -G "${CMAKE_GENERATOR}" -DFMT_TEST=OFF
    ${BUILD_SYSTEM} -j$PARALLEL
    ${BUILD_SYSTEM} install
}

#ryu
build_ryu() {
    check_if_source_exist $RYU_SOURCE
    cd $TP_SOURCE_DIR/$RYU_SOURCE/ryu
    make -j$PARALLEL
    make install DESTDIR=${TP_INSTALL_DIR}
    mkdir -p $TP_INSTALL_DIR/include/ryu
    mv $TP_INSTALL_DIR/include/ryu.h $TP_INSTALL_DIR/include/ryu
    # copy to 64 to compatable with current CMake
    cp -f ${TP_INSTALL_DIR}/lib/libryu.a ${TP_INSTALL_DIR}/lib64/libryu.a
}

#break_pad
build_breakpad() {
    check_if_source_exist $BREAK_PAD_SOURCE
    cd $TP_SOURCE_DIR/$BREAK_PAD_SOURCE
    mkdir -p src/third_party/lss
    cp $TP_PATCH_DIR/linux_syscall_support.h src/third_party/lss
    LDFLAGS="-L${TP_LIB_DIR}" \
    CFLAGS= ./configure --prefix=$TP_INSTALL_DIR --enable-shared=no --disable-samples --disable-libevent-regress
    make -j$PARALLEL
    make install
}

#hadoop
build_hadoop() {
    check_if_source_exist $HADOOP_SOURCE
    cp -r $TP_SOURCE_DIR/$HADOOP_SOURCE $TP_INSTALL_DIR/hadoop
    # remove unnecessary doc and logs
    rm -rf $TP_INSTALL_DIR/hadoop/logs/* $TP_INSTALL_DIR/hadoop/share/doc/hadoop
    mkdir -p $TP_INSTALL_DIR/include/hdfs
    cp $TP_SOURCE_DIR/$HADOOP_SOURCE/include/hdfs.h $TP_INSTALL_DIR/include/hdfs
    cp $TP_SOURCE_DIR/$HADOOP_SOURCE/lib/native/libhdfs.a $TP_INSTALL_DIR/lib
}

#jdk
build_jdk() {
    check_if_source_exist $JDK_SOURCE
    rm -rf $TP_INSTALL_DIR/open_jdk && cp -r $TP_SOURCE_DIR/$JDK_SOURCE $TP_INSTALL_DIR/open_jdk
}

# ragel
# ragel-6.9+ is used by hypercan, so we build it first.
build_ragel() {
    check_if_source_exist $RAGEL_SOURCE
    cd $TP_SOURCE_DIR/$RAGEL_SOURCE
    # generage a static linked ragel, hyperscan will depend on it
    LDFLAGS=" -static-libstdc++ -static-libgcc" \
    ./configure --prefix=$TP_INSTALL_DIR --disable-shared --enable-static
    make -j$PARALLEL
    make install
}

#hyperscan
build_hyperscan() {
    check_if_source_exist $HYPERSCAN_SOURCE
    cd $TP_SOURCE_DIR/$HYPERSCAN_SOURCE
    export PATH=$TP_INSTALL_DIR/bin:$PATH
    $CMAKE_CMD -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX=${TP_INSTALL_DIR} -DBOOST_ROOT=$STARROCKS_THIRDPARTY/installed/include \
          -DCMAKE_CXX_COMPILER=$STARROCKS_GCC_HOME/bin/g++ -DCMAKE_C_COMPILER=$STARROCKS_GCC_HOME/bin/gcc  -DCMAKE_INSTALL_LIBDIR=lib \
          -DBUILD_EXAMPLES=OFF -DBUILD_UNIT=OFF
    ${BUILD_SYSTEM} -j$PARALLEL
    ${BUILD_SYSTEM} install
}

#mariadb-connector-c
build_mariadb() {
    OLD_CMAKE_GENERATOR=${CMAKE_GENERATOR}
    OLD_BUILD_SYSTEM=${BUILD_SYSTEM}

    unset CXXFLAGS
    unset CPPFLAGS
    export CFLAGS="-O3 -fno-omit-frame-pointer -fPIC ${FILE_PREFIX_MAP_OPTION}"

    # force use make build system, since ninja doesn't support install only headers
    CMAKE_GENERATOR="Unix Makefiles"
    BUILD_SYSTEM='make'

    check_if_source_exist $MARIADB_SOURCE
    cd $TP_SOURCE_DIR/$MARIADB_SOURCE
    mkdir -p build && cd build

    $CMAKE_CMD .. -G "${CMAKE_GENERATOR}" -DCMAKE_BUILD_TYPE=Release    \
                  -DWITH_UNIT_TESTS=OFF                                 \
                  -DBUILD_SHARED_LIBS=OFF                               \
                  -DOPENSSL_ROOT_DIR=${TP_INSTALL_DIR}                  \
                  -DOPENSSL_USE_STATIC_LIBS=TRUE                        \
                  -DCMAKE_INSTALL_PREFIX=${TP_INSTALL_DIR}
    # we only need build libmariadbclient and headers
    ${BUILD_SYSTEM} -j$PARALLEL mariadbclient
    cd $TP_SOURCE_DIR/$MARIADB_SOURCE/build/libmariadb
    mkdir -p $TP_INSTALL_DIR/lib/mariadb/
    cp libmariadbclient.a $TP_INSTALL_DIR/lib/mariadb/
    # install mariadb headers
    cd $TP_SOURCE_DIR/$MARIADB_SOURCE/build/include
    ${BUILD_SYSTEM} install

    restore_compile_flags
    export CMAKE_GENERATOR=$OLD_CMAKE_GENERATOR
    export BUILD_SYSTEM=$OLD_BUILD_SYSTEM
}

# jindosdk for Aliyun OSS
build_aliyun_jindosdk() {
    check_if_source_exist $JINDOSDK_SOURCE
    mkdir -p $TP_INSTALL_DIR/jindosdk
    cp -r $TP_SOURCE_DIR/$JINDOSDK_SOURCE/lib/*.jar $TP_INSTALL_DIR/jindosdk
}

build_gcs_connector() {
    check_if_source_exist $GCS_CONNECTOR_SOURCE
    mkdir -p $TP_INSTALL_DIR/gcs_connector
    cp -r $TP_SOURCE_DIR/$GCS_CONNECTOR_SOURCE/*.jar $TP_INSTALL_DIR/gcs_connector
}

build_aws_cpp_sdk() {
    check_if_source_exist $AWS_SDK_CPP_SOURCE
    cd $TP_SOURCE_DIR/$AWS_SDK_CPP_SOURCE
    # only build s3, s3-crt, transfer manager, identity-management and sts, you can add more components if you want.
    $CMAKE_CMD -Bbuild -DBUILD_ONLY="core;s3;s3-crt;transfer;identity-management;sts;kms" -DCMAKE_BUILD_TYPE=RelWithDebInfo \
               -DBUILD_SHARED_LIBS=OFF -DCMAKE_INSTALL_PREFIX=${TP_INSTALL_DIR} -DENABLE_TESTING=OFF \
               -DENABLE_CURL_LOGGING=OFF \
               -G "${CMAKE_GENERATOR}" \
               -DCURL_LIBRARY_RELEASE=${TP_INSTALL_DIR}/lib/libcurl.a   \
               -DZLIB_LIBRARY_RELEASE=${TP_INSTALL_DIR}/lib/libz.a      \
               -DOPENSSL_ROOT_DIR=${TP_INSTALL_DIR}                     \
               -DOPENSSL_USE_STATIC_LIBS=TRUE                           \
               -Dcrypto_LIBRARY=${TP_INSTALL_DIR}/lib/libcrypto.a

    cd build
    ${BUILD_SYSTEM} -j$PARALLEL
    ${BUILD_SYSTEM} install

    restore_compile_flags
}

# velocypack
build_vpack() {
    check_if_source_exist $VPACK_SOURCE
    cd $TP_SOURCE_DIR/$VPACK_SOURCE
    mkdir -p build
    cd build
    $CMAKE_CMD .. \
        -DCMAKE_CXX_STANDARD="17" \
        -G "${CMAKE_GENERATOR}" \
        -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX=${TP_INSTALL_DIR} \
        -DCMAKE_CXX_COMPILER=$STARROCKS_GCC_HOME/bin/g++ -DCMAKE_C_COMPILER=$STARROCKS_GCC_HOME/bin/gcc

    ${BUILD_SYSTEM} -j$PARALLEL
    ${BUILD_SYSTEM} install
}

# opentelemetry
build_opentelemetry() {
    check_if_source_exist $OPENTELEMETRY_SOURCE

    cd $TP_SOURCE_DIR/$OPENTELEMETRY_SOURCE
    mkdir -p $BUILD_DIR
    cd $BUILD_DIR
    rm -rf CMakeCache.txt CMakeFiles/
    $CMAKE_CMD .. \
        -DCMAKE_CXX_STANDARD="17" \
        -G "${CMAKE_GENERATOR}" \
        -DCMAKE_INSTALL_PREFIX=${TP_INSTALL_DIR} \
        -DBUILD_TESTING=OFF -DWITH_EXAMPLES=OFF \
        -DCMAKE_INSTALL_LIBDIR=lib64 \
        -DWITH_STL=OFF -DWITH_JAEGER=ON
    ${BUILD_SYSTEM} -j$PARALLEL
    ${BUILD_SYSTEM} install
}

# jemalloc
build_jemalloc() {
    check_if_source_exist $JEMALLOC_SOURCE

    cd $TP_SOURCE_DIR/$JEMALLOC_SOURCE
    # jemalloc supports a runtime page size that's smaller or equal to the build
    # time one, but aborts on a larger one. If not defined, it falls back to the
    # the build system's _SC_PAGESIZE, which in many architectures can vary. Set
    # this to 64K (2^16) for arm architecture, and default 4K on x86 for performance.
    local addition_opts=" --with-lg-page=12"
    if [[ $MACHINE_TYPE == "aarch64" ]] ; then
        # change to 64K for arm architecture
        addition_opts=" --with-lg-page=16"
    fi
    # build jemalloc with release
    CFLAGS="-O3 -fno-omit-frame-pointer -fPIC -g" \
    ./configure --prefix=${TP_INSTALL_DIR}/jemalloc --with-jemalloc-prefix=je --enable-prof --disable-cxx --disable-libdl $addition_opts
    make -j$PARALLEL
    make install
    mkdir -p ${TP_INSTALL_DIR}/jemalloc/lib-shared/
    mkdir -p ${TP_INSTALL_DIR}/jemalloc/lib-static/
    mv ${TP_INSTALL_DIR}/jemalloc/lib/*.so* ${TP_INSTALL_DIR}/jemalloc/lib-shared/
    mv ${TP_INSTALL_DIR}/jemalloc/lib/*.a ${TP_INSTALL_DIR}/jemalloc/lib-static/
    # build jemalloc with debug options
    CFLAGS="-O3 -fno-omit-frame-pointer -fPIC -g" \
    ./configure --prefix=${TP_INSTALL_DIR}/jemalloc-debug --with-jemalloc-prefix=je --enable-prof --disable-static --enable-debug --enable-fill --enable-prof --disable-cxx --disable-libdl $addition_opts
    make -j$PARALLEL
    make install
}

# google benchmark
build_benchmark() {
    check_if_source_exist $BENCHMARK_SOURCE
    cd $TP_SOURCE_DIR/$BENCHMARK_SOURCE
    mkdir -p $BUILD_DIR
    cd $BUILD_DIR
    rm -rf CMakeCache.txt CMakeFiles/
    # https://github.com/google/benchmark/issues/773
    cmake -DBENCHMARK_DOWNLOAD_DEPENDENCIES=off \
          -DBENCHMARK_ENABLE_GTEST_TESTS=off \
          -DCMAKE_INSTALL_PREFIX=$TP_INSTALL_DIR \
          -DCMAKE_INSTALL_LIBDIR=lib64 \
          -DRUN_HAVE_STD_REGEX=0 \
          -DRUN_HAVE_POSIX_REGEX=0 \
          -DCOMPILE_HAVE_GNU_POSIX_REGEX=0 \
          -DCMAKE_BUILD_TYPE=Release ../
    ${BUILD_SYSTEM} -j$PARALLEL
    ${BUILD_SYSTEM} install
}

# fast float
build_fast_float() {
    check_if_source_exist $FAST_FLOAT_SOURCE
    cd $TP_SOURCE_DIR/$FAST_FLOAT_SOURCE
    cp -r $TP_SOURCE_DIR/$FAST_FLOAT_SOURCE/include $TP_INSTALL_DIR
}

build_starcache() {
    check_if_source_exist $STARCACHE_SOURCE
    rm -rf $TP_INSTALL_DIR/$STARCACHE_SOURCE && mv $TP_SOURCE_DIR/$STARCACHE_SOURCE $TP_INSTALL_DIR/
}

# streamvbyte
build_streamvbyte() {
    check_if_source_exist $STREAMVBYTE_SOURCE

    cd $TP_SOURCE_DIR/$STREAMVBYTE_SOURCE/

    mkdir -p $BUILD_DIR
    cd $BUILD_DIR
    rm -rf CMakeCache.txt CMakeFiles/

    CMAKE_GENERATOR="Unix Makefiles"
    BUILD_SYSTEM='make'
    $CMAKE_CMD .. -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX:PATH=$TP_INSTALL_DIR/

    make -j$PARALLEL
    make install
}

# jansson
build_jansson() {
    check_if_source_exist $JANSSON_SOURCE
    cd $TP_SOURCE_DIR/$JANSSON_SOURCE/
    mkdir -p build
    cd build
    $CMAKE_CMD .. -DCMAKE_INSTALL_PREFIX=${TP_INSTALL_DIR} -DCMAKE_INSTALL_LIBDIR=lib
    ${BUILD_SYSTEM} -j$PARALLEL
    ${BUILD_SYSTEM} install
}

# avro-c
build_avro_c() {
    check_if_source_exist $AVRO_SOURCE
    cd $TP_SOURCE_DIR/$AVRO_SOURCE/lang/c
    mkdir -p build
    cd build
    $CMAKE_CMD .. -DCMAKE_INSTALL_PREFIX=${TP_INSTALL_DIR} -DCMAKE_INSTALL_LIBDIR=lib64 -DCMAKE_BUILD_TYPE=Release
    ${BUILD_SYSTEM} -j$PARALLEL
    ${BUILD_SYSTEM} install
    rm ${TP_INSTALL_DIR}/lib64/libavro.so*
}

# avro-cpp
build_avro_cpp() {
    check_if_source_exist $AVRO_SOURCE
    cd $TP_SOURCE_DIR/$AVRO_SOURCE/lang/c++
    mkdir -p build
    cd build
    $CMAKE_CMD .. -DCMAKE_BUILD_TYPE=Release -DBOOST_ROOT=${TP_INSTALL_DIR} -DBoost_USE_STATIC_RUNTIME=ON  -DCMAKE_PREFIX_PATH=${TP_INSTALL_DIR} -DSNAPPY_INCLUDE_DIR=${TP_INSTALL_DIR}/include -DSNAPPY_LIBRARIES=${TP_INSTALL_DIR}/lib
    LIBRARY_PATH=${TP_INSTALL_DIR}/lib64:$LIBRARY_PATH LD_LIBRARY_PATH=${STARROCKS_GCC_HOME}/lib64:$LD_LIBRARY_PATH ${BUILD_SYSTEM} -j$PARALLEL

    # cp include and lib
    cp libavrocpp_s.a ${TP_INSTALL_DIR}/lib64/
    cp -r ../include/avro ${TP_INSTALL_DIR}/include/avrocpp
}

# serders
build_serdes() {
    export CFLAGS="-O3 -fno-omit-frame-pointer -fPIC -g"
    check_if_source_exist $SERDES_SOURCE
    cd $TP_SOURCE_DIR/$SERDES_SOURCE
    export LIBS="-lrt -lpthread -lcurl -ljansson -lrdkafka -lrdkafka++ -lavro -lssl -lcrypto -ldl"
    ./configure --prefix=${TP_INSTALL_DIR} \
                --libdir=${TP_INSTALL_DIR}/lib \
                --CFLAGS="-I ${TP_INSTALL_DIR}/include"  \
                --CXXFLAGS="-I ${TP_INSTALL_DIR}/include" \
                --LDFLAGS="-L ${TP_INSTALL_DIR}/lib -L ${TP_INSTALL_DIR}/lib64" \
                --enable-static \
                --disable-shared

    make -j$PARALLEL
    make install
    rm ${TP_INSTALL_DIR}/lib/libserdes.so*
    # these symbols also be definition in librdkafka, change these symbols to be local.
    objcopy --localize-symbol=cnd_timedwait ${TP_INSTALL_DIR}/lib/libserdes.a
    objcopy --localize-symbol=cnd_timedwait_ms ${TP_INSTALL_DIR}/lib/libserdes.a
    objcopy --localize-symbol=thrd_is_current ${TP_INSTALL_DIR}/lib/libserdes.a
    unset LIBS
    restore_compile_flags
}

# datasketches
build_datasketches() {
    check_if_source_exist $DATASKETCHES_SOURCE
    mkdir -p $TP_INSTALL_DIR/include/datasketches
    cp -r $TP_SOURCE_DIR/$DATASKETCHES_SOURCE/common/include/* $TP_INSTALL_DIR/include/datasketches/
    cp -r $TP_SOURCE_DIR/$DATASKETCHES_SOURCE/cpc/include/* $TP_INSTALL_DIR/include/datasketches/
    cp -r $TP_SOURCE_DIR/$DATASKETCHES_SOURCE/fi/include/* $TP_INSTALL_DIR/include/datasketches/
    cp -r $TP_SOURCE_DIR/$DATASKETCHES_SOURCE/hll/include/* $TP_INSTALL_DIR/include/datasketches/
    cp -r $TP_SOURCE_DIR/$DATASKETCHES_SOURCE/kll/include/* $TP_INSTALL_DIR/include/datasketches/
    cp -r $TP_SOURCE_DIR/$DATASKETCHES_SOURCE/quantiles/include/* $TP_INSTALL_DIR/include/datasketches/
    cp -r $TP_SOURCE_DIR/$DATASKETCHES_SOURCE/req/include/* $TP_INSTALL_DIR/include/datasketches/
    cp -r $TP_SOURCE_DIR/$DATASKETCHES_SOURCE/sampling/include/* $TP_INSTALL_DIR/include/datasketches/
    cp -r $TP_SOURCE_DIR/$DATASKETCHES_SOURCE/theta/include/* $TP_INSTALL_DIR/include/datasketches/
    cp -r $TP_SOURCE_DIR/$DATASKETCHES_SOURCE/tuple/include/* $TP_INSTALL_DIR/include/datasketches/
}

# async-profiler
build_async_profiler() {
    check_if_source_exist $ASYNC_PROFILER_SOURCE
    mkdir -p $TP_INSTALL_DIR/async-profiler
    cp -r $TP_SOURCE_DIR/$ASYNC_PROFILER_SOURCE/bin $TP_INSTALL_DIR/async-profiler
    cp -r $TP_SOURCE_DIR/$ASYNC_PROFILER_SOURCE/lib $TP_INSTALL_DIR/async-profiler
}

# fiu
build_fiu() {
    check_if_source_exist $FIU_SOURCE
    cd $TP_SOURCE_DIR/$FIU_SOURCE
    mkdir -p $TP_SOURCE_DIR/$FIU_SOURCE/installed
    make -j$PARALLEL
    make PREFIX=$TP_SOURCE_DIR/$FIU_SOURCE/installed install

    mkdir -p $TP_INSTALL_DIR/include/fiu
    cp $TP_SOURCE_DIR/$FIU_SOURCE/installed/include/* $TP_INSTALL_DIR/include/fiu/
    cp $TP_SOURCE_DIR/$FIU_SOURCE/installed/lib/libfiu.a $TP_INSTALL_DIR/lib/
}

# libdeflate
build_libdeflate() {
    check_if_source_exist $LIBDEFLATE_SOURCE
    mkdir -p $TP_SOURCE_DIR/$LIBDEFLATE_SOURCE/build
    cd $TP_SOURCE_DIR/$LIBDEFLATE_SOURCE/build
    $CMAKE_CMD .. -DCMAKE_INSTALL_LIBDIR=lib64 -DCMAKE_INSTALL_PREFIX=${TP_INSTALL_DIR} -DCMAKE_BUILD_TYPE=Release
    ${BUILD_SYSTEM} -j$PARALLEL
    ${BUILD_SYSTEM} install
}

#clucene
build_clucene() {
    check_if_source_exist "${CLUCENE_SOURCE}"
    cd "$TP_SOURCE_DIR/${CLUCENE_SOURCE}"

    mkdir -p "${BUILD_DIR}"
    cd "${BUILD_DIR}"
    rm -rf CMakeCache.txt CMakeFiles/

    ${CMAKE_CMD} -G "${CMAKE_GENERATOR}" \
        -DCMAKE_INSTALL_PREFIX="$TP_INSTALL_DIR" \
        -DCMAKE_INSTALL_LIBDIR=lib64 \
        -DBUILD_STATIC_LIBRARIES=ON \
        -DBUILD_SHARED_LIBRARIES=OFF \
        -DBOOST_ROOT="$TP_INSTALL_DIR" \
        -DZLIB_ROOT="$TP_INSTALL_DIR" \
        -DCMAKE_CXX_FLAGS="-g -fno-omit-frame-pointer -Wno-narrowing ${FILE_PREFIX_MAP_OPTION}" \
        -DUSE_STAT64=0 \
        -DCMAKE_BUILD_TYPE=Release \
        -DUSE_AVX2=$THIRD_PARTY_BUILD_WITH_AVX2 \
        -DBUILD_CONTRIBS_LIB=ON ..
    ${BUILD_SYSTEM} -j "${PARALLEL}"
    ${BUILD_SYSTEM} install

    cd "$TP_SOURCE_DIR/${CLUCENE_SOURCE}"
    if [[ ! -d "$TP_INSTALL_DIR"/share ]]; then
        mkdir -p "$TP_INSTALL_DIR"/share
    fi
}

build_absl() {
    check_if_source_exist "${ABSL_SOURCE}"
    cd "$TP_SOURCE_DIR/${ABSL_SOURCE}"

    ${CMAKE_CMD} -G "${CMAKE_GENERATOR}" \
        -DCMAKE_INSTALL_LIBDIR=lib \
        -DCMAKE_INSTALL_PREFIX="$TP_INSTALL_DIR" \
        -DCMAKE_CXX_STANDARD=17

    ${BUILD_SYSTEM} -j "${PARALLEL}"
    ${BUILD_SYSTEM} install
}

build_grpc() {
    check_if_source_exist "${GRPC_SOURCE}"
    cd "$TP_SOURCE_DIR/${GRPC_SOURCE}"

    mkdir -p "${BUILD_DIR}"
    cd "${BUILD_DIR}"
    rm -rf CMakeCache.txt CMakeFiles/

    ${CMAKE_CMD} -G "${CMAKE_GENERATOR}" \
        -DCMAKE_PREFIX_PATH=${TP_INSTALL_DIR}               \
        -DCMAKE_INSTALL_PREFIX=${TP_INSTALL_DIR}            \
        -DgRPC_INSTALL=ON                                   \
        -DgRPC_BUILD_TESTS=OFF                              \
        -DgRPC_BUILD_CSHARP_EXT=OFF                         \
        -DgRPC_BUILD_GRPC_RUBY_PLUGIN=OFF                   \
        -DgRPC_BUILD_GRPC_PYTHON_PLUGIN=OFF                 \
        -DgRPC_BUILD_GRPC_PHP_PLUGIN=OFF                    \
        -DgRPC_BUILD_GRPC_OBJECTIVE_C_PLUGIN=OFF            \
        -DgRPC_BUILD_GRPC_NODE_PLUGIN=OFF                   \
        -DgRPC_BUILD_GRPC_CSHARP_PLUGIN=OFF                 \
        -DgRPC_BACKWARDS_COMPATIBILITY_MODE=ON              \
        -DgRPC_SSL_PROVIDER=package                         \
        -DOPENSSL_ROOT_DIR=${TP_INSTALL_DIR}                \
        -DOPENSSL_USE_STATIC_LIBS=TRUE                      \
        -DgRPC_ZLIB_PROVIDER=package                        \
        -DZLIB_LIBRARY_RELEASE=${TP_INSTALL_DIR}/lib/libz.a \
        -DgRPC_ABSL_PROVIDER=package                        \
        -Dabsl_DIR=${TP_INSTALL_DIR}/lib/cmake/absl         \
        -DgRPC_PROTOBUF_PROVIDER=package                    \
        -DgRPC_RE2_PROVIDER=package                         \
        -DRE2_INCLUDE_DIR=${TP_INSTALL_DIR}/include    \
        -DRE2_LIBRARY=${TP_INSTALL_DIR}/libre2.a \
        -DgRPC_CARES_PROVIDER=module                        \
        -DCARES_ROOT_DIR=$TP_SOURCE_DIR/$CARES_SOURCE/      \
        -DCMAKE_EXE_LINKER_FLAGS="-static-libstdc++ -static-libgcc" \
        -DCMAKE_CXX_STANDARD=17 ..

    ${BUILD_SYSTEM} -j "${PARALLEL}"
    ${BUILD_SYSTEM} install
}

build_simdutf() {
    check_if_source_exist "${SIMDUTF_SOURCE}"
    cd "$TP_SOURCE_DIR/${SIMDUTF_SOURCE}"

    ${CMAKE_CMD} -G "${CMAKE_GENERATOR}" \
        -DCMAKE_INSTALL_LIBDIR=lib \
        -DCMAKE_INSTALL_PREFIX="$TP_INSTALL_DIR"    \
        -DSIMDUTF_TESTS=OFF \
        -DSIMDUTF_TOOLS=OFF \
        -DSIMDUTF_ICONV=OFF

    ${BUILD_SYSTEM} -j "${PARALLEL}"
    ${BUILD_SYSTEM} install
}

# tenann
build_tenann() {
    check_if_source_exist $TENANN_SOURCE
    rm -rf $TP_INSTALL_DIR/include/tenann
    rm -rf $TP_INSTALL_DIR/lib/libtenann-bundle.a
    rm -rf $TP_INSTALL_DIR/lib/libtenann-bundle-avx2.a
    cp -r $TP_SOURCE_DIR/$TENANN_SOURCE/include/tenann $TP_INSTALL_DIR/include/tenann
    cp -r $TP_SOURCE_DIR/$TENANN_SOURCE/lib/libtenann-bundle.a $TP_INSTALL_DIR/lib/
    cp -r $TP_SOURCE_DIR/$TENANN_SOURCE/lib/libtenann-bundle-avx2.a $TP_INSTALL_DIR/lib/
}

build_icu() {
    check_if_source_exist $ICU_SOURCE
    cd $TP_SOURCE_DIR/$ICU_SOURCE/source

    sed -i 's/\r$//' ./runConfigureICU
    sed -i 's/\r$//' ./config.*
    sed -i 's/\r$//' ./configure
    sed -i 's/\r$//' ./mkinstalldirs

    unset CPPFLAGS
    unset CXXFLAGS
    unset CFLAGS

    # Use a subshell to prevent LD_LIBRARY_PATH from affecting the external environment
    (
        export LD_LIBRARY_PATH=${STARROCKS_GCC_HOME}/lib:${STARROCKS_GCC_HOME}/lib64:${LD_LIBRARY_PATH:-}
        export CFLAGS="-O3 -fno-omit-frame-pointer -fPIC"
        export CXXFLAGS="-O3 -fno-omit-frame-pointer -fPIC"
        ./runConfigureICU Linux --prefix=$TP_INSTALL_DIR --enable-static --disable-shared
        make -j$PARALLEL
        make install
    )
    restore_compile_flags
}

build_xsimd() {
    check_if_source_exist $XSIMD_SOURCE
    cd $TP_SOURCE_DIR/$XSIMD_SOURCE

    # xsimd only has header files
    ${CMAKE_CMD} -G "${CMAKE_GENERATOR}" \
        -DCMAKE_INSTALL_LIBDIR=lib \
        -DCMAKE_INSTALL_PREFIX="$TP_INSTALL_DIR"
    ${BUILD_SYSTEM} install
}

build_libxml2() {
    check_if_source_exist $LIBXML2_SOURCE
    cd $TP_SOURCE_DIR/$LIBXML2_SOURCE

    ${CMAKE_CMD} -DCMAKE_INSTALL_PREFIX=$TP_INSTALL_DIR \
        -DBUILD_SHARED_LIBS=OFF \
        -DLIBXML2_WITH_ICONV=OFF \
        -DLIBXML2_WITH_LZMA=OFF \
        -DLIBXML2_WITH_PYTHON=OFF \
        -DLIBXML2_WITH_ZLIB=OFF \
        -DLIBXML2_WITH_TESTS=OFF \
        -DCMAKE_INSTALL_LIBDIR=lib

    ${BUILD_SYSTEM} -j "${PARALLEL}"
    ${BUILD_SYSTEM} install
}

build_azure() {
    check_if_source_exist $AZURE_SOURCE
    cd $TP_SOURCE_DIR/$AZURE_SOURCE

    export AZURE_SDK_DISABLE_AUTO_VCPKG=true
    export PKG_CONFIG_LIBDIR=$TP_INSTALL_DIR

    ${CMAKE_CMD} -DCMAKE_INSTALL_PREFIX=$TP_INSTALL_DIR \
        -DBUILD_SHARED_LIBS=OFF \
        -DDISABLE_AZURE_CORE_OPENTELEMETRY=ON \
        -DWARNINGS_AS_ERRORS=OFF \
        -DCURL_INCLUDE_DIR=$TP_INSTALL_DIR/include \
        -DCURL_LIBRARY=$TP_INSTALL_DIR/lib/libcurl.a \
        -DOPENSSL_ROOT_DIR=$TP_INSTALL_DIR \
        -DOPENSSL_USE_STATIC_LIBS=TRUE \
        -DLibXml2_ROOT=$TP_INSTALL_DIR \
        -DCMAKE_INSTALL_LIBDIR=lib

    ${BUILD_SYSTEM} -j "${PARALLEL}"
    ${BUILD_SYSTEM} install

    unset AZURE_SDK_DISABLE_AUTO_VCPKG
    unset PKG_CONFIG_LIBDIR
}

build_libdivide() {
    check_if_source_exist $LIBDIVIDE_SOURCE
    cd $TP_SOURCE_DIR/$LIBDIVIDE_SOURCE

    $CMAKE_CMD . -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX:PATH=$TP_INSTALL_DIR/

    ${BUILD_SYSTEM} -j "${PARALLEL}"
    ${BUILD_SYSTEM} install
}

# restore cxxflags/cppflags/cflags to default one
restore_compile_flags() {
    # c preprocessor flags
    export CPPFLAGS=$GLOBAL_CPPFLAGS
    # c flags
    export CFLAGS=$GLOBAL_CFLAGS
    # c++ flags
    export CXXFLAGS=$GLOBAL_CXXFLAGS
}

strip_binary() {
    # strip binary tools and ignore any errors
    echo "Strip binaries in $TP_INSTALL_DIR/bin/ ..."
    strip $TP_INSTALL_DIR/bin/* 2>/dev/null || true
}


# strip `$TP_SOURCE_DIR` and `$TP_INSTALL_DIR` from source code file path
export FILE_PREFIX_MAP_OPTION="-ffile-prefix-map=${TP_SOURCE_DIR}=. -ffile-prefix-map=${TP_INSTALL_DIR}=."
# set GLOBAL_C*FLAGS for easy restore in each sub build process
export GLOBAL_CPPFLAGS="-I${TP_INCLUDE_DIR} "
export GLOBAL_CFLAGS="-O3 -fno-omit-frame-pointer -std=gnu17 -fPIC -g -gz=zlib ${FILE_PREFIX_MAP_OPTION}"
export GLOBAL_CXXFLAGS="-O3 -fno-omit-frame-pointer -Wno-class-memaccess -fPIC -g -gz=zlib ${FILE_PREFIX_MAP_OPTION}"

# set those GLOBAL_*FLAGS to the CFLAGS/CXXFLAGS/CPPFLAGS
export CPPFLAGS=$GLOBAL_CPPFLAGS
export CXXFLAGS=$GLOBAL_CXXFLAGS
export CFLAGS=$GLOBAL_CFLAGS

# Define default build order
declare -a all_packages=(
    libevent
    zlib
    lz4
    lzo2
    bzip
    openssl
    boost # must before thrift
    protobuf
    gflags
    gtest
    glog
    rapidjson
    simdjson
    snappy
    gperftools
    curl
    re2
    thrift
    leveldb
    brpc
    rocksdb
    kerberos
    # must build before arrow
    sasl
    absl
    grpc
    flatbuffers
    jemalloc
    brotli
    arrow
    # NOTE: librdkafka depends on ZSTD which is generated by Arrow, So this SHOULD be
    # built after arrow
    librdkafka
    pulsar
    s2
    bitshuffle
    croaringbitmap
    cctz
    fmt
    ryu
    hadoop
    jdk
    ragel
    hyperscan
    mariadb
    aliyun_jindosdk
    gcs_connector
    aws_cpp_sdk
    vpack
    opentelemetry
    benchmark
    fast_float
    starcache
    streamvbyte
    jansson
    avro_c
    avro_cpp
    serdes
    datasketches
    async_profiler
    fiu
    llvm
    clucene
    simdutf
    poco
    icu
    xsimd
    libxml2
    azure
    libdivide
)

# Machine specific packages
if [[ "${MACHINE_TYPE}" != "aarch64" ]]; then
    all_packages+=(breakpad libdeflate tenann)
fi

# Initialize packages array - if none specified, build all
if [[ "${#packages[@]}" -eq 0 ]]; then
    packages=("${all_packages[@]}")
fi

# Build packages
PACKAGE_FOUND=0
for package in "${packages[@]}"; do
    if [[ "${package}" == "${start_package}" ]]; then
        PACKAGE_FOUND=1
    fi
    if [[ "${CONTINUE}" -eq 0 ]] || [[ "${PACKAGE_FOUND}" -eq 1 ]]; then
        command="build_${package}"
        ${command}
    fi
done

# strip unnecessary debug symbol for binaries in thirdparty
strip_binary

echo "Finished to build all thirdparties"
