/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

/**
 * @test
 * @bug 8257531
 * @summary Test vectorization for Buffer operations.
 * @library /test/lib /
 * @requires vm.compiler2.enabled & vm.debug == true
 * @requires os.arch=="x86" | os.arch=="i386" | os.arch=="amd64" | os.arch=="x86_64" | os.arch=="aarch64"
 * @run main compiler.vectorization.TestBufferVectorization array
 * @run main compiler.vectorization.TestBufferVectorization arrayOffset
 * @run main compiler.vectorization.TestBufferVectorization buffer
 * @run main compiler.vectorization.TestBufferVectorization bufferHeap
 * @run main compiler.vectorization.TestBufferVectorization bufferDirect
 */

package compiler.vectorization;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

import jdk.test.lib.process.ProcessTools;
import jdk.test.lib.process.OutputAnalyzer;

public class TestBufferVectorization {
    final static int N = 500;
    final static int ITER = 1000;
    final static IntBuffer buffer = IntBuffer.allocate(N);
    final static int offset = buffer.arrayOffset();
    final static IntBuffer heap_buffer_byte_to_int = ByteBuffer.allocate(N * Integer.BYTES).order(ByteOrder.nativeOrder()).asIntBuffer();
    final static IntBuffer direct_buffer_byte_to_int = ByteBuffer.allocateDirect(N * Integer.BYTES).order(ByteOrder.nativeOrder()).asIntBuffer();
    final static String arch = System.getProperty("os.arch");

    interface Test {
        void init();
        void run();
        void verify();
    }

    static class TestArray implements Test {
        final int[] array = new int[N];

        public void init() {
            for (int k = 0; k < array.length; k++) {
                array[k] = k;
            }
        }

        public void run() {
            for(int k = 0; k < array.length; k++) {
                array[k] += 1;
            }
        }

        public void verify() {
            init(); // reset
            run();  // run compiled code
            for(int k = 0; k < array.length; k++) {
                if (array[k] != (k + 1)) {
                    throw new RuntimeException(" Invalid result: array[" + k + "]: " + array[k] + " != " + (k + 1));
                }
            }
        }
    }

    static class TestArrayOffset implements Test {
        final int offset;
        final int[] array = new int[N];

        public TestArrayOffset(int off) {
            offset = off;
        }

        public void init() {
            for (int k = 0; k < array.length; k++) {
                array[k] = k;
            }
        }

        public void run() {
            int l = array.length - offset;
            for(int k = 0; k < l; k++) {
                array[k + offset] += 1;
            }
        }

        public void verify() {
            init(); // reset
            run();  // run compiled code
            int l = array.length - offset;
            for(int k = 0; k < l; k++) {
                if (array[k] != (k + 1)) {
                    throw new RuntimeException(" Invalid result: arrayOffset[" + k + "]: " + array[k] + " != " + (k + 1));
                }
            }
            for(int k = l; k < array.length; k++) {
                if (array[k] != k) {
                    throw new RuntimeException(" Invalid result: arrayOffset[" + k + "]: " + array[k] + " != " + k);
                }
            }
        }
    }

    static class TestBuffer implements Test {
        final IntBuffer buffer;

        public TestBuffer(IntBuffer buf) {
            buffer = buf;
        }

        public void init() {
            for (int k = 0; k < buffer.limit(); k++) {
                buffer.put(k, k);
            }
        }

        public void run() {
            for (int k = 0; k < buffer.limit(); k++) {
                buffer.put(k, buffer.get(k) + 1);
            }
        }

        public void verify() {
            init(); // reset
            run();  // run compiled code
            for(int k = 0; k < buffer.limit(); k++) {
                if (buffer.get(k) != (k + 1)) {
                    throw new RuntimeException(" Invalid result: buffer.get(" + k + "): " + buffer.get(k) + " != " + (k + 1));
                }
            }
        }
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            throw new RuntimeException(" Missing test name: array, arrayOffset, buffer, bufferHeap, bufferDirect");
        }

        Test te;
        switch (args[0]) {
            case "array":
                te = new TestArray();
                break;
            case "arrayOffset":
                te = new TestArrayOffset(offset);
                break;
            case "buffer":
                te = new TestBuffer(buffer);
                break;
            case "bufferHeap":
                te = new TestBuffer(heap_buffer_byte_to_int);
                break;
            case "bufferDirect":
                te = new TestBuffer(direct_buffer_byte_to_int);
                break;
            default:
                throw new RuntimeException(" Unknown test: " + args[0]);
        }

        te.init();
        for (int i = 0; i < ITER; i++) {
            te.run();
        }
        te.verify();

        if (args.length == 1) {
            verify_vectors(te, args[0]);
        }
    }

    static void verify_vectors(Test t, String testName) {
        if (testName.equals("bufferDirect")) {
            return; // bufferDirect uses Unsafe memory accesses which are not vectorized currently
        }

        if (testName.equals("bufferHeap") && (arch.equals("x86") || arch.equals("i386"))) {
            return; // bufferHeap uses Long type for memory accesses which are not vectorized in 32-bit VM
        }

        ProcessBuilder pb;
        OutputAnalyzer out;
        try {
            pb = ProcessTools.createJavaProcessBuilder("-XX:-BackgroundCompilation",
                                                       "-XX:+TraceNewVectors",
                                                       "compiler.vectorization.TestBufferVectorization",
                                                       testName,
                                                       "skip_verify");
            out = new OutputAnalyzer(pb.start());
        } catch (Exception e) {
            throw new RuntimeException(" Exception launching Java process: " + e);
        }
        out.shouldContain("ReplicateI");
        out.shouldContain("LoadVector");
        out.shouldContain("AddVI");
        out.shouldContain("StoreVector");
        out.shouldHaveExitValue(0);
    }
}