ResNet with unlimited\* accumulators and cache:

|Array Size|256|128|64|32|16|8|4|
|---|---|---|---|---|---|---|--|
|Array utilization (%)|31.5|57.2|76.8|83.4|98.2|98.4|99.3|
|Latency (MCycles)|1.3|1.2|1.3|1.8|3|5.4|10.8
|Energy (MUnits)|1802|519|458|679|691|859|1041

ResNet with limited accumulators and cache:

|Array Size|256|128|64|32|16|8|4|
|---|---|---|---|---|---|---|--|
|Cache Size|4096|2048|2048|2048|2048|2048|2048|
|Accumulator Size|512|512|512|512|512|512|512|
|Array utilization (%)|31.5|57.2|76.8|83.4|98.2|98.4|99.3|
|Accumulator utilization (%)|93.6|84.4|83.8|75.1|61.5|48.4|51.5|
|Cache utilization (%)|39.1|51.7|51.7|60.9|67.6|80.5|76.3|
|Latency (MCycles)|3.5|3|10.6|20.8|71|153.2|286.1
|Energy (MUnits)|21561|5567|1782|1183|1821|3151|7800

------------
\* set to nearest power of 2 greater than maximum usage
