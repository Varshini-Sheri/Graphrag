#!/bin/bash
sbt clean job/assembly
docker build -t graphrag:latest -f deploy/docker/Dockerfile .
