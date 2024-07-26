bm=$1

useHUS=1
alpha=1.1
beta=2
timingDriven=true

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

echo "(/usr/bin/time ./bin/rapidwright PartialRouterPhysNetlist benchmarks/${bm}_unrouted.phys output/${bm}_cufr.phys $parameters) 2>&1 | tee log/${bm}_cufr.phys.log"
(/usr/bin/time ./bin/rapidwright PartialRouterPhysNetlist benchmarks/${bm}_unrouted.phys output/${bm}_cufr.phys $parameters) 2>&1 | tee log/${bm}_cufr.phys.log