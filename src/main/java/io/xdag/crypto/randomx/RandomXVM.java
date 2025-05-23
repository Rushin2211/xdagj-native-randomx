/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2022-2030 The XdagJ Developers
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package io.xdag.crypto.randomx;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;

/**
 * Wrapper class for RandomX virtual machine operations.
 * Manages the lifecycle and state of a RandomX VM instance.
 */
@Slf4j
public class RandomXVM implements AutoCloseable {
    /**
     * The RandomX flags used to configure this VM.
     */
    @Getter
    private final Set<RandomXFlag> flags;

    /**
     * Pointer to the native VM instance.
     */
    @Getter
    private final Pointer vmPointer;

    /**
     * The cache used by this VM.
     */
    @Getter
    private RandomXCache cache;

    /**
     * The dataset used by this VM (may be null in light mode).
     */
    @Getter
    private RandomXDataset dataset;

    /**
     * Creates a new RandomX VM instance with the specified configuration.
     *
     * @param flags Configuration flags for the VM.
     * @param cache The cache to use for VM operations.
     * @param dataset The dataset to use for VM operations (may be null for light mode).
     * @throws RuntimeException if VM creation fails.
     * @throws IllegalArgumentException if parameters are invalid.
     */
    public RandomXVM(Set<RandomXFlag> flags, RandomXCache cache, RandomXDataset dataset) {
        if (flags == null || flags.isEmpty()) {
            throw new IllegalArgumentException("Flags cannot be null or empty.");
        }
        if (cache == null || cache.getCachePointer() == null) {
            throw new IllegalArgumentException("Cache instance or its pointer cannot be null.");
        }
        // Dataset can be null (light mode)
        if (dataset != null && dataset.getDatasetPointer() == null) {
            throw new IllegalArgumentException("If a dataset is provided, its pointer cannot be null.");
        }

        this.flags = flags;
        this.cache = cache;
        this.dataset = dataset;

        int flagsValue = RandomXFlag.toValue(flags);
        Pointer cachePtr = cache.getCachePointer();
        Pointer datasetPtr = (dataset != null) ? dataset.getDatasetPointer() : null;

        log.debug("Preparing to create RandomX VM. Flags: {} ({}), Cache Ptr: {}, Dataset Ptr: {}",
            flags, flagsValue, Pointer.nativeValue(cachePtr), (datasetPtr != null ? Pointer.nativeValue(datasetPtr) : "null"));

        this.vmPointer = RandomXNative.randomx_create_vm(flagsValue, cachePtr, datasetPtr);

        if (vmPointer == null) {
            String errorMsg = String.format("Failed to create RandomX VM with flags: %s (%d)", flags, flagsValue);
            log.error(errorMsg);
            throw new RuntimeException(errorMsg);
        }

        log.info("RandomX VM created successfully. Pointer: {}, Flags: {}", Pointer.nativeValue(vmPointer), flags);
    }

    /**
     * Updates the cache used by this VM.
     *
     * @param newCache The new cache to use.
     * @throws IllegalArgumentException if newCache is null or its pointer is null.
     * @throws IllegalStateException if the VM pointer is null.
     */
    public void setCache(RandomXCache newCache) {
        if (vmPointer == null) {
            throw new IllegalStateException("VM pointer is null, cannot set cache.");
        }
        if (newCache == null || newCache.getCachePointer() == null) {
            throw new IllegalArgumentException("New cache instance or its pointer cannot be null.");
        }
        RandomXNative.randomx_vm_set_cache(vmPointer, newCache.getCachePointer());
        this.cache = newCache;
        log.debug("VM cache updated. New Cache Ptr: {}", Pointer.nativeValue(newCache.getCachePointer()));
    }

    /**
     * Updates the dataset used by this VM.
     *
     * @param newDataset The new dataset to use (can be null for light mode).
     * @throws IllegalArgumentException if newDataset is not null but its pointer is null.
     * @throws IllegalStateException if the VM pointer is null.
     */
    public void setDataset(RandomXDataset newDataset) {
        if (vmPointer == null) {
            throw new IllegalStateException("VM pointer is null, cannot set dataset.");
        }
        // If newDataset is not null, its pointer also cannot be null
        if (newDataset != null && newDataset.getDatasetPointer() == null) {
            throw new IllegalArgumentException("If a new dataset is provided, its pointer cannot be null.");
        }
        Pointer datasetPtr = (newDataset != null) ? newDataset.getDatasetPointer() : null;
        RandomXNative.randomx_vm_set_dataset(vmPointer, datasetPtr);
        this.dataset = newDataset;
        log.debug("VM dataset updated. New Dataset Ptr: {}", (datasetPtr != null ? Pointer.nativeValue(datasetPtr) : "null"));
    }

    /**
     * Calculates a RandomX hash using the current VM configuration.
     *
     * @param input The input data to be hashed.
     * @return A 32-byte array containing the calculated hash.
     * @throws IllegalArgumentException if input is null.
     * @throws IllegalStateException if the VM pointer is null.
     */
    public byte[] calculateHash(byte[] input) {
        if (vmPointer == null) {
            throw new IllegalStateException("VM pointer is null, cannot calculate hash.");
        }
        if (input == null) {
            throw new IllegalArgumentException("Input cannot be null.");
        }
        byte[] output = new byte[32]; // RandomX hash is always 32 bytes

        // JNA Memory objects automatically manage native memory allocation and deallocation
        // (at the end of their scope or during GC).
        Memory inputMem = new Memory(input.length > 0 ? input.length : 1); // JNA Memory does not accept size 0
        Memory outputMem = new Memory(output.length);
      if (input.length > 0) {
          inputMem.write(0, input, 0, input.length);
      }
      RandomXNative.randomx_calculate_hash(vmPointer, inputMem, input.length, outputMem);
      outputMem.read(0, output, 0, output.length);
      return output;
    }

    /**
     * Begins a multi-part hash calculation.
     *
     * @param input The input data.
     * @throws IllegalArgumentException if input is null.
     * @throws IllegalStateException if the VM pointer is null.
     */
    public void calculateHashFirst(byte[] input) {
        if (vmPointer == null) {
            throw new IllegalStateException("VM pointer is null, cannot start multi-part hash.");
        }
        if (input == null) {
            throw new IllegalArgumentException("Input cannot be null.");
        }
        Memory inputMem = new Memory(input.length > 0 ? input.length : 1);
      if (input.length > 0) {
         inputMem.write(0, input, 0, input.length);
     }
      RandomXNative.randomx_calculate_hash_first(vmPointer, inputMem, input.length);
    }

    /**
     * Continues a multi-part hash calculation.
     *
     * @param input The input data.
     * @return A 32-byte array containing the intermediate hash result.
     * @throws IllegalArgumentException if input is null.
     * @throws IllegalStateException if the VM pointer is null.
     */
    public byte[] calculateHashNext(byte[] input) {
        if (vmPointer == null) {
            throw new IllegalStateException("VM pointer is null, cannot continue multi-part hash.");
        }
        if (input == null) {
            throw new IllegalArgumentException("Input cannot be null.");
        }
        byte[] output = new byte[32];
        Memory inputMem = new Memory(input.length > 0 ? input.length : 1);
        Memory outputMem = new Memory(output.length);
      if (input.length > 0) {
          inputMem.write(0, input, 0, input.length);
      }
      RandomXNative.randomx_calculate_hash_next(vmPointer, inputMem, input.length, outputMem);
      outputMem.read(0, output, 0, output.length);
      return output;
    }

    /**
     * Finalizes a multi-part hash calculation.
     *
     * @return A 32-byte array containing the final hash result.
     * @throws IllegalStateException if the VM pointer is null.
     */
    public byte[] calculateHashLast() {
        if (vmPointer == null) {
            throw new IllegalStateException("VM pointer is null, cannot finalize multi-part hash.");
        }
        byte[] output = new byte[32];
        Memory outputMem = new Memory(output.length);
      RandomXNative.randomx_calculate_hash_last(vmPointer, outputMem);
      outputMem.read(0, output, 0, output.length);
      return output;
    }

    /**
     * Calculates a commitment hash for the given input data.
     * Note: The implementation of this method is based on observation of the original code.
     * It first calculates a regular hash, then uses that hash as a seed to calculate the commitment.
     * The exact behavior and signature of the {@code randomx_calculate_commitment} C API should be confirmed.
     *
     * @param originalInput The original input data that was hashed.
     * @param preCalculatedHash The hash previously calculated from originalInput.
     * @return A 32-byte array containing the calculated commitment.
     * @throws IllegalArgumentException if originalInput or preCalculatedHash is null, or if preCalculatedHash is not 32 bytes.
     * @throws IllegalStateException if the VM pointer is null.
     */
    public byte[] calculateCommitment(byte[] originalInput, byte[] preCalculatedHash) {
        if (vmPointer == null) {
            throw new IllegalStateException("VM pointer is null, cannot calculate commitment.");
        }
        if (originalInput == null) {
            throw new IllegalArgumentException("Original input cannot be null.");
        }
        if (preCalculatedHash == null || preCalculatedHash.length != RandomXUtils.RANDOMX_HASH_SIZE) {
            throw new IllegalArgumentException("Pre-calculated hash cannot be null and must be " + RandomXUtils.RANDOMX_HASH_SIZE + " bytes long.");
        }

        byte[] commitmentOutput = new byte[RandomXUtils.RANDOMX_HASH_SIZE];
        Memory originalInputMem = new Memory(originalInput.length > 0 ? originalInput.length : 1); // JNA requires non-zero size for empty inputs
        Memory preCalculatedHashMem = new Memory(preCalculatedHash.length);
        Memory commitmentOutputMem = new Memory(commitmentOutput.length);

      if (originalInput.length > 0) {
          originalInputMem.write(0, originalInput, 0, originalInput.length);
      }
      // preCalculatedHash is guaranteed to be non-null and 32 bytes here
      preCalculatedHashMem.write(0, preCalculatedHash, 0, preCalculatedHash.length);

      // Call the native method with parameters matching the C API
      // (Pointer input, long inputSize, Pointer hash_in, Pointer com_out)
      RandomXNative.randomx_calculate_commitment(originalInputMem, originalInput.length, preCalculatedHashMem, commitmentOutputMem);
      commitmentOutputMem.read(0, commitmentOutput, 0, commitmentOutput.length);
      return commitmentOutput;
    }

    /**
     * Releases native VM resources.
     * This method is idempotent and can be called multiple times safely.
     */
    @Override
    public void close() {
        if (vmPointer != null) {
            // Check if vmPointer is still valid to prevent operations on an already destroyed VM.
            // While JNA's destroy is generally safe, this is an extra layer of protection.
            try {
                RandomXNative.randomx_destroy_vm(vmPointer);
                log.info("RandomX VM destroyed. Pointer: {}", Pointer.nativeValue(vmPointer));
            } catch (Throwable t) {
                // Generally, destroy should not throw an error, but just in case.
                log.error("Error occurred while destroying RandomX VM. Pointer: {}", Pointer.nativeValue(vmPointer), t);
            }
            // Do not set vmPointer to null as it is final. The object itself will no longer be usable.
        } else {
            log.debug("Attempting to destroy RandomX VM, but pointer is already null (possibly already destroyed or never created).");
        }
    }
}