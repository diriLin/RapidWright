#!/bin/bash

# prepare
all_benchmarks=(
            logicnets_jscl \
            boom_med_pb           \
            vtr_mcml                  \
            rosetta_fd                \
            corundum_25g                \
            finn_radioml  \
            vtr_lu64peeng             \
            corescore_500             \
            corescore_500_pb          \
            mlcad_d181_lefttwo3rds    \
            koios_dla_like_large      \
            boom_soc                  \
            ispd16_example2 \
            rapidwright_picoblazearray \
            corundum_100g \
            koios_clstm_like_large \
            titan23_orig_gsm_x6 \
            corescore_900 \
            koios_dla_like_large_v2 \
            finn_mobilenetv1 \
            ispd16_fpga03 \
            mlcad_d181 \
            boom_soc_v2 \
            corescore_1200 \
            ispd16_example2_v2 \
            corescore_1500 \
            titan23_orig_dart_x4 \
            corescore_1700 \
            )

time=$(date -d now '+%Y-%m-%d-%H-%M-%S')
root_dir=$(pwd)
output_dir="$root_dir/output/$time"
log_dir="$root_dir/log/$time"
tmp_run_dir="tmp/$time"

mkdir -p tmp
mkdir -p $tmp_run_dir
mkdir -p $log_dir
mkdir -p $output_dir
cp -r bin $tmp_run_dir
cp -r build $tmp_run_dir
ln -s $(pwd)/jars $tmp_run_dir
ln -s $(pwd)/benchmarks $tmp_run_dir
ln -s $(pwd)/data $tmp_run_dir
ln -s $(pwd)/timing $tmp_run_dir
ln -s $(pwd)/tcl $tmp_run_dir

# parameters
useHUS=1
timingDriven=1
alpha=1.1
beta=2

parameters=""
if [ $timingDriven ]
then
    parameters="$parameters --timingDriven"
fi
if [ $useHUS ]
then
    parameters="$parameters --useHUS --HUSAlpha $alpha --HUSBeta $beta"
fi
(echo $parameters) | tee $log_dir/info

# run
cd $tmp_run_dir
./bin/rapidwright Jython -c "FileTools.ensureDataFilesAreStaticInstallFriendly('xcvu3p')"
for bm in ${all_benchmarks[@]}
do
    echo "(/usr/bin/time ./bin/rapidwright PartialRouterPhysNetlist benchmarks/${bm}_unrouted.phys ${output_dir}/${bm}_cufr.phys $parameters) 2>&1 | tee ${log_dir}/${bm}_cufr.phys.log"
    (/usr/bin/time ./bin/rapidwright PartialRouterPhysNetlist benchmarks/${bm}_unrouted.phys ${output_dir}/${bm}_cufr.phys $parameters) 2>&1 | tee ${log_dir}/${bm}_cufr.phys.log
done
cd $root_dir