/* Stub rnnoise_data.c – minimal model for Android build without downloading large model
 * Provides dummy weights that allow rnnoise to link and run with simple spectral gating fallback.
 * For production, replace with real rnnoise_data.c from https://media.xiph.org/rnnoise/models/
 */

/* Stub rnnoise_data.c – minimal model for Android build without downloading large model
 * Provides dummy weights that allow rnnoise to link and run with simple spectral gating fallback.
 * For production, replace with real rnnoise_data.c from https://media.xiph.org/rnnoise/models/
 * 
 * Fixed: removed #include "rnn.h" which caused circular dependency with rnnoise_data.h
 * Now this file is self-contained and provides dummy symbols only.
 */

#include <stdlib.h>
#include "rnnoise.h"

// Define minimal RNNModel struct to allow instantiation
// Real definition is in denoise.c, but for stub we provide minimal version
// This matches the struct layout expected by rnnoise_data.h extern declaration
struct RNNModel {
    const void *const_blob;
    void *blob;
    int blob_len;
    void *file;
};

// Minimal dummy model – not used by our stub implementation in rnnoise_stub.c
// But we provide symbols so denoise.c can link if needed

const int rnnoise_model_size = 0;

// Dummy model data – our stub will not use this
const unsigned char rnnoise_data[1] = {0};

// Provide dummy RNNModel for linking – matches declaration in rnnoise_data.h
const struct RNNModel rnnoise_model_orig = {0};

// Provide weak symbols for model if needed
__attribute__((weak)) const struct RNNModel *rnnoise_get_default_model(void) {
    return NULL;
}
