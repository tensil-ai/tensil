/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

#include <malloc.h>
#include <stdio.h>

#include "ff.h"
#include "platform.h"
#include "xgpiops.h"
#include "xil_printf.h"

#include "tensil/dram.h"
#include "tensil/driver.h"
#include "tensil/instruction.h"
#include "tensil/model.h"
#include "tensil/tcu.h"

#include "console.h"
#include "stopwatch.h"

static error_t driver_run_timed(struct driver *driver,
                                const struct run_opts *run_opts) {
    struct stopwatch sw;

    error_t error = stopwatch_start(&sw);

    if (error)
        return error;

    error = driver_run(driver, run_opts);

    if (error)
        return error;

    stopwatch_stop(&sw);

    printf("Program run took %.2f us\n", stopwatch_elapsed_us(&sw));

    return ERROR_NONE;
}

static const char *data_type_to_string(enum data_type type) {
    switch (type) {
    case TENSIL_DATA_TYPE_FP16BP8:
    default:
        return "FP16BP8";
    }
}

static size_t argmax(size_t size, const float *buffer) {
    if (!size)
        return -1;

    float max = buffer[0];
    size_t max_i = 0;

    for (size_t i = 1; i < size; i++)
        if (buffer[i] > max) {
            max = buffer[i];
            max_i = i;
        }

    return max_i;
}

#define CHANNEL_TO_FLOAT(v) ((float)v / 255.0)

static float channel_mean(size_t size, const u8 *buffer) {
    float sum = 0.0;
    for (size_t i = 0; i < size; i++)
        sum += CHANNEL_TO_FLOAT(buffer[i]);

    return sum / (float)size;
}

struct leds {
    XGpioPs gpio;
};

static void leds_init_pin(struct leds *leds, uint32_t pin) {
    XGpioPs_SetDirectionPin(&leds->gpio, pin, 1);
    XGpioPs_SetOutputEnablePin(&leds->gpio, pin, 1);
}

#define LEDS_PIN_FIRST 17
#define LEDS_PIN_LAST 20

#define LEDS_COUNT (LEDS_PIN_LAST - LEDS_PIN_FIRST + 1)

static error_t leds_init(struct leds *leds) {
    XGpioPs_Config *config;
    config = XGpioPs_LookupConfig(XPAR_XGPIOPS_0_DEVICE_ID);
    if (!config)
        return DRIVER_ERROR(ERROR_DRIVER_AXI_DMA_DEVICE_NOT_FOUND,
                            "Leds GPIO not found");

    int status = XGpioPs_CfgInitialize(&leds->gpio, config, config->BaseAddr);
    if (status != XST_SUCCESS)
        return XILINX_ERROR(status);

    for (uint32_t i = 0; i < LEDS_COUNT; i++)
        leds_init_pin(leds, LEDS_PIN_FIRST + i);

    return ERROR_NONE;
}

static void leds_show_bits(struct leds *leds, uint32_t bits) {
    for (uint32_t i = 0; i < LEDS_COUNT; i++) {
        XGpioPs_WritePin(&leds->gpio, LEDS_PIN_LAST - i, bits & 1);
        bits >>= 1;
    }
}

#define CIFAR_PIXELS_SIZE 1024
#define CIFAR_CLASSES_SIZE 10

#ifdef TENSIL_PLATFORM_SAMPLE_BUFFER_HIGH
#define CIFAR_BUFFER_BASE TENSIL_PLATFORM_SAMPLE_BUFFER_HIGH
#else
#define CIFAR_BUFFER_BASE TENSIL_PLATFORM_DRAM_BUFFER_HIGH
#endif

static const char *cifar_classes[] = {
    "airplane", "automobile", "bird",  "cat",  "deer",
    "dog",      "frog",       "horse", "ship", "truck",
};

static const char progress[] = {'-', '\\', '|', '/'};

static error_t test_resnet20v2_on_cifar(struct driver *driver,
                                        const struct model *model,
                                        const char *file_name,
                                        bool print_images) {
    FIL fil;
    FILINFO fno;
    UINT bytes_read;
    error_t error = ERROR_NONE;

    FRESULT res = f_stat(file_name, &fno);
    if (res)
        return FS_ERROR(res);

    res = f_open(&fil, file_name, FA_READ);

    if (res)
        return FS_ERROR(res);

    printf("Reading CIFAR test images from %s...\n", file_name);

    res = f_read(&fil, (void *)CIFAR_BUFFER_BASE, fno.fsize, &bytes_read);
    f_close(&fil);

    if (res)
        return FS_ERROR(res);

    size_t total_count = fno.fsize / (CIFAR_PIXELS_SIZE * 3 + 1);
    size_t misclass_count = 0;
    u8 *ptr = (u8 *)CIFAR_BUFFER_BASE;

    struct leds leds;
    error = leds_init(&leds);

    if (error)
        return error;

    printf("Testing ResNet20V2 on CIFAR...\n");

    float total_seconds = 0;

    if (print_images)
        console_clear_screen();

    for (size_t i = 0; i < total_count; i++) {
        leds_show_bits(&leds, i);

        size_t expected_class = *ptr;
        ptr += 1;

        u8 *red = ptr;
        ptr += CIFAR_PIXELS_SIZE;

        u8 *green = ptr;
        ptr += CIFAR_PIXELS_SIZE;

        u8 *blue = ptr;
        ptr += CIFAR_PIXELS_SIZE;

        float red_mean = channel_mean(CIFAR_PIXELS_SIZE, red);
        float green_mean = channel_mean(CIFAR_PIXELS_SIZE, green);
        float blue_mean = channel_mean(CIFAR_PIXELS_SIZE, blue);

        for (size_t j = 0; j < CIFAR_PIXELS_SIZE; j++) {
            float pixel[] = {CHANNEL_TO_FLOAT(red[j]) - red_mean,
                             CHANNEL_TO_FLOAT(green[j]) - green_mean,
                             CHANNEL_TO_FLOAT(blue[j]) - blue_mean};

            error = driver_load_model_input_vector_scalars(driver, model, "x",
                                                           j, 3, pixel);

            if (error)
                goto cleanup;
        }

        struct stopwatch sw;
        error = stopwatch_start(&sw);

        if (error)
            goto cleanup;

        error = driver_run(driver, NULL);

        if (error)
            goto cleanup;

        stopwatch_stop(&sw);
        float seconds = stopwatch_elapsed_seconds(&sw);

        total_seconds += seconds;

        float result[CIFAR_CLASSES_SIZE];
        error = driver_get_model_output_scalars(driver, model, "Identity",
                                                CIFAR_CLASSES_SIZE, result);

        if (error)
            goto cleanup;

        size_t actual_class = argmax(CIFAR_CLASSES_SIZE, result);

        if (actual_class != expected_class)
            misclass_count++;

        if (print_images) {
            console_set_cursor_position(1, 1);
            printf("%06zu: %.2f fps %c\n", i, (float)1 / seconds,
                   progress[i % 4]);

            if (i % 100 == 0) {
                printf("\nImage:");

                for (size_t j = 0; j < CIFAR_PIXELS_SIZE; j++) {
                    console_set_background_color(red[j], green[j], blue[j]);

                    if (j % 32 == 0)
                        printf("\n");

                    printf("  ");
                }

                printf("\n");
                console_reset_background_color();

                printf("\nResult:\n");

                error = driver_print_model_output_vectors(driver, model,
                                                          "Identity");

                if (error)
                    goto cleanup;

                if (actual_class == expected_class)
                    console_set_foreground_color(0, 255, 0);
                else
                    console_set_foreground_color(255, 0, 0);

                printf(
                    "CIFAR expected class = %s, actual class = %s         \n",
                    cifar_classes[expected_class], cifar_classes[actual_class]);

                console_reset_foreground_color();
            }
        }
    }

cleanup:
    if (print_images) {
        console_clear_screen();
        console_set_cursor_position(1, 1);
    }

    if (error == ERROR_NONE)
        printf("ResNet20V2 on CIFAR: %lu images %.2f accuracy at %.2f fps\n",
               total_count, (1.0 - (float)misclass_count / (float)total_count),
               (float)total_count / total_seconds);

    return error;
}

static FATFS fatfs;

#define IMAGENET_CLASSES_SIZE 1000
#define IMAGENET_CLASSES_BUFFER_SIZE (32 * 1024)

static char imagenet_classes_buffer[IMAGENET_CLASSES_BUFFER_SIZE];
static const char *imagenet_classes[IMAGENET_CLASSES_SIZE];

static error_t load_imagenet_classes_from_file(const char *file_name) {
    FIL fil;
    FILINFO fno;
    FRESULT res;
    UINT bytes_read;

    memset(&fno, 0, sizeof(FILINFO));
    res = f_stat(file_name, &fno);
    if (res)
        return FS_ERROR(res);

    if (fno.fsize > IMAGENET_CLASSES_BUFFER_SIZE)
        return FS_ERROR(1000);

    memset(&fil, 0, sizeof(FIL));
    res = f_open(&fil, file_name, FA_READ);
    if (res)
        return FS_ERROR(res);

    res = f_read(&fil, (void *)imagenet_classes_buffer, fno.fsize, &bytes_read);
    f_close(&fil);

    if (res)
        return FS_ERROR(res);

    char *curr_ptr = imagenet_classes_buffer;

    for (size_t i = 0; i < IMAGENET_CLASSES_SIZE; i++) {
        imagenet_classes[i] = curr_ptr;

        while (*(++curr_ptr) != '\n')
            ;

        *curr_ptr = 0;
        curr_ptr++;
    }

    return ERROR_NONE;
}

#define FILE_NAME_BUFFER_SIZE 256

int main() {
    init_platform();

    error_t error = ERROR_NONE;
    FRESULT res;
    res = f_mount(&fatfs, "0:/", 0);

    if (res) {
        error = FS_ERROR(res);
        goto cleanup;
    }

    struct driver driver;
    error = driver_init(&driver);

    if (error)
        goto cleanup;

    printf("Ultra96v2 ---------------------------------------\n");
    printf("Array (vector) size:               %zu\n", driver.arch.array_size);
    printf("Data type:                         %s\n",
           data_type_to_string(driver.arch.data_type));
    printf("Local memory size (vectors):       %zu\n", driver.arch.local_depth);
    printf("Accumulator memory size (vectors): %zu\n",
           driver.arch.accumulator_depth);
    printf("DRAM0 size (vectors):              %zu\n", driver.arch.dram0_depth);
    printf("DRAM1 size (vectors):              %zu\n", driver.arch.dram1_depth);
    printf("Stride #0:                         %zu\n",
           driver.arch.stride0_depth);
    printf("Stride #1:                         %zu\n",
           driver.arch.stride1_depth);
    printf("SIMD registers:                    %zu\n",
           driver.arch.simd_registers_depth);
    printf("Program buffer size (bytes):       %zu\n", driver.buffer.size);
#ifdef TENSIL_PLATFORM_SAMPLE_AXI_DMA_DEVICE_ID
    printf("Samples buffer size (bytes):       %zu\n",
           driver.sample_buffer.size);
#endif
    printf("DRAM0 size (bytes):                %zu\n", driver.dram0_size);
    printf("DRAM1 size (bytes):                %zu\n", driver.dram1_size);

    printf("Testing sampling...\n");
    error = driver_run_sampling_test(&driver, false);

    if (error)
        goto cleanup;

    printf("Testing memory (DRAM0 -> DRAM0)...\n");
    error = driver_run_memory_test(&driver, DRAM0, DRAM0, false);

    if (error)
        goto cleanup;

    printf("Testing memory (DRAM1 -> DRAM0)...\n");
    error = driver_run_memory_test(&driver, DRAM1, DRAM0, false);

    if (error)
        goto cleanup;

    printf("Testing systolic array...\n");
    error = driver_run_array_test(&driver, true);

    if (error)
        goto cleanup;

    printf("Testing SIMD...\n");
    error = driver_run_simd_test(&driver, true);

    if (error)
        goto cleanup;

    printf("XOR4 ---------------------------------------\n");

    struct model xor4_model;
    error = model_from_file(&xor4_model, "xor4_ultra.tmodel");

    if (error)
        goto cleanup;

    error = driver_load_model(&driver, &xor4_model);

    if (error)
        goto cleanup;

    for (int x0 = 0; x0 <= 1; x0++)
        for (int x1 = 0; x1 <= 1; x1++) {
            float x[] = {x0, x1};
            error = driver_load_model_input_scalars(&driver, &xor4_model, "x",
                                                    2, x);

            if (error)
                goto cleanup;

            error = driver_run_timed(&driver, NULL);

            if (error)
                goto cleanup;

            error = driver_print_model_output_vectors(&driver, &xor4_model,
                                                      "Identity");

            if (error)
                goto cleanup;
        }

    printf("ResNet20V2 ---------------------------------------\n");

    struct model resnet20v2_model;
    error = model_from_file(&resnet20v2_model, "resnet20v2_cifar_ultra.tmodel");

    if (error)
        goto cleanup;

    error = driver_load_model(&driver, &resnet20v2_model);

    if (error)
        goto cleanup;

    error = driver_load_model_input_from_file(&driver, &resnet20v2_model, "x",
                                              "resnet_input_1x32x32x16.tdata");

    if (error)
        goto cleanup;

    struct run_opts resnet20v2_run_opts = {
        .print_sampling_aggregates = true,
        .print_sampling_listing = true,
        .print_sampling_summary = true,
        .sample_file_name = "resnet20v2_cifar_ultra.tsample"};

    error = driver_run_timed(&driver, &resnet20v2_run_opts);

    if (error)
        goto cleanup;

    error = driver_print_model_output_vectors(&driver, &resnet20v2_model,
                                              "Identity");

    if (error)
        goto cleanup;

    float cifar_result[CIFAR_CLASSES_SIZE];
    error =
        driver_get_model_output_scalars(&driver, &resnet20v2_model, "Identity",
                                        CIFAR_CLASSES_SIZE, cifar_result);

    if (error)
        goto cleanup;

    size_t cifar_class = argmax(CIFAR_CLASSES_SIZE, cifar_result);
    printf("%zu, (%s)\n", cifar_class, cifar_classes[cifar_class]);

    error = test_resnet20v2_on_cifar(&driver, &resnet20v2_model,
                                     "test_batch.bin", false);

    if (error)
        goto cleanup;

    printf("YoloV4-tiny ---------------------------------------\n");

    struct model yolov4_tiny_model;
    error = model_from_file(&yolov4_tiny_model, "yolov4_tiny_192_ultra.tmodel");

    if (error)
        goto cleanup;

    error = driver_load_model(&driver, &yolov4_tiny_model);

    if (error)
        goto cleanup;

    error = driver_load_model_input_from_file(&driver, &yolov4_tiny_model, "x",
                                              "yolov4_tiny_1x192x192x16.tdata");

    if (error)
        goto cleanup;

    struct run_opts yolov4_tiny_run_opts = {
        .print_sampling_aggregates = true,
        .print_sampling_listing = false,
        .print_sampling_summary = true,
        .sample_file_name = "yolov4_tiny_192_ultra.tsample"};

    error = driver_run_timed(&driver, &yolov4_tiny_run_opts);

    if (error)
        goto cleanup;

    error = driver_print_model_output_vectors(&driver, &yolov4_tiny_model,
                                              "model/conv2d_17/BiasAdd");

    if (error)
        goto cleanup;

    error = driver_print_model_output_vectors(&driver, &yolov4_tiny_model,
                                              "model/conv2d_20/BiasAdd");

    if (error)
        goto cleanup;

    printf("ResNet50V2 ---------------------------------------\n");

    error = load_imagenet_classes_from_file("imagenet_classes.txt");

    if (error)
        goto cleanup;

    struct model resnet50v2_model;
    error =
        model_from_file(&resnet50v2_model, "resnet50v2_imagenet_ultra.tmodel");

    if (error)
        goto cleanup;

    error = driver_load_model(&driver, &resnet50v2_model);

    if (error)
        goto cleanup;

    struct run_opts resnet50v2_run_opts = {
        .print_sampling_aggregates = true,
        .print_sampling_listing = false,
        .print_sampling_summary = true,
        .sample_file_name = "resnet50v2_imagenet_ultra.tsample"};

    for (int i = 0; i < 3; i++) {
        char file_name_buffer[FILE_NAME_BUFFER_SIZE];
        snprintf(file_name_buffer, FILE_NAME_BUFFER_SIZE,
                 "resnet_input_1x224x224x16_%d.tdata", i);

        error = driver_load_model_input_from_file(&driver, &resnet50v2_model,
                                                  "x", file_name_buffer);

        if (error)
            goto cleanup;

        error = driver_run_timed(&driver, &resnet50v2_run_opts);

        if (error)
            goto cleanup;

        error = driver_print_model_output_vectors(&driver, &resnet50v2_model,
                                                  "Identity");

        if (error)
            goto cleanup;

        float imagenet_result[IMAGENET_CLASSES_SIZE];
        error = driver_get_model_output_scalars(
            &driver, &resnet50v2_model, "Identity", IMAGENET_CLASSES_SIZE,
            imagenet_result);

        if (error)
            goto cleanup;

        size_t imagenet_class = argmax(IMAGENET_CLASSES_SIZE, imagenet_result);
        printf("%zu (%s)\n", imagenet_class, imagenet_classes[imagenet_class]);
    }

cleanup:
    if (error)
        error_print(error);

    cleanup_platform();

    return 0;
}
