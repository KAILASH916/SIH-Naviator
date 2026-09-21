# Neural Network Model Provenance

This document records technical metadata and execution parameters for NAVIATOR's on-device AI Dead Reckoning model.

## Model Summary

- **Filename**: `gru_io_vnbd.onnx` (located in `app/src/main/assets/gru_io_vnbd.onnx`)
- **Runtime Environment**: ONNX Runtime Android (`ai.onnxruntime:onnxruntime-android`)
- **Model Architecture**: Gated Recurrent Unit (GRU) for Inertial Navigation & Velocity Estimation (IO-VNBD Native 10 Hz)
- **Input Tensor Shape**: `[batch, 20, 6]` (20 timesteps @ 10 Hz = 2.0 second IMU window; 6 channels: 3-axis linear acceleration + 3-axis angular velocity)
- **Output Tensor Shape**: `[batch, 3]` (3D metric displacement vector `[dx, dy, dz]` in meters)
- **Inference Location**: 100% on-device local execution (zero cloud latency or internet connectivity required)

## Runtime Preprocessing & Normalization

- **Normalization Parameters File**: `input_normalization.json`
- **Acceleration Feature Normalization**: Standardized using mean and standard deviation per axis (m/s² converted to g)
- **Gyroscope Feature Normalization**: Standardized using mean and standard deviation per axis (rad/s)
- **Inference Latency Target**: < 15 ms per 200ms stride window on modern mobile CPUs

## Project Training & Dataset Status

> [!NOTE]
> Training dataset: NOT PROVIDED IN CURRENT PROJECT
> Training pipeline: NOT PROVIDED IN CURRENT PROJECT
> Evaluation dataset: NOT PROVIDED IN CURRENT PROJECT

*The bundled ONNX model `gru_io_vnbd.onnx` is deployed pre-compiled for local forward inference within the EKF fusion loop.*
