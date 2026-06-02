// SherpaOnnx-Bridging-Header.h
// Exposes the sherpa-onnx C API and the ONNX Runtime C API to Swift.
// Copyright (c) 2023 Xiaomi Corporation
#ifndef SHERPAONNX_BRIDGING_HEADER_H_
#define SHERPAONNX_BRIDGING_HEADER_H_

#import "sherpa-onnx/c-api/c-api.h"

// ONNX Runtime generic C API — used directly by M2m100OnnxEngine for MT inference.
// Headers live in onnxruntime.xcframework/Headers/ (already on HEADER_SEARCH_PATHS).
#import "onnxruntime_c_api.h"

#endif // SHERPAONNX_BRIDGING_HEADER_H_
