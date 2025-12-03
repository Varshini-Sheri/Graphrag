#!/bin/bash
kubectl apply -f deploy/ollama-daemonset.yaml
kubectl apply -f deploy/job-graph-rag.yaml
