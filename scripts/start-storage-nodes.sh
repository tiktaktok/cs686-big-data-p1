#!/usr/bin/env bash

if [ -z "$1" ]
then
    echo "missing argument: controller-host"
    exit 1
fi

for i in 14 15 16 17 18 20 21 22 23 24
do
echo "Starting storage node on bass${i}..."
ssh bass${i} controller_host=$1 storage_node_host=bass${i} 'bash -s' << 'ENDSSH'
nohup ./storage-node.sh $controller_host > logs/storage-node-$storage_node_host &
ENDSSH
done
