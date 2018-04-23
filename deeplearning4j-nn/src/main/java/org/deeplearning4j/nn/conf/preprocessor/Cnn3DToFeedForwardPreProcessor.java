/*-
 *
 *  * Copyright 2015 Skymind,Inc.
 *  *
 *  *    Licensed under the Apache License, Version 2.0 (the "License");
 *  *    you may not use this file except in compliance with the License.
 *  *    You may obtain a copy of the License at
 *  *
 *  *        http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *    Unless required by applicable law or agreed to in writing, software
 *  *    distributed under the License is distributed on an "AS IS" BASIS,
 *  *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *    See the License for the specific language governing permissions and
 *  *    limitations under the License.
 *
 */

package org.deeplearning4j.nn.conf.preprocessor;

import lombok.Data;
import org.deeplearning4j.nn.api.MaskState;
import org.deeplearning4j.nn.conf.InputPreProcessor;
import org.deeplearning4j.nn.conf.inputs.InputType;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.api.shape.Shape;
import org.nd4j.linalg.primitives.Pair;
import org.nd4j.shade.jackson.annotation.JsonCreator;
import org.nd4j.shade.jackson.annotation.JsonProperty;

import java.util.Arrays;

/**
 * A preprocessor to allow CNN and standard feed-forward network layers to be used together.<br>
 * For example, CNN3D -> Denselayer <br>
 * This does two things:<br>
 * (b) Reshapes 5d activations out of CNN layer, with shape
 * [numExamples, numChannels, inputDepth, inputHeight, inputWidth]) into 2d activations (with shape
 * [numExamples, inputDepth*inputHeight*inputWidth*numChannels]) for use in feed forward layer
 * (a) Reshapes epsilons (weights*deltas) out of FeedFoward layer (which is 2D or 3D with shape
 * [numExamples, inputDepth* inputHeight*inputWidth*numChannels]) into 5d epsilons (with shape
 * [numExamples, numChannels, inputDepth, inputHeight, inputWidth]) suitable to feed into CNN layers.<br>
 * Note: numChannels is equivalent to featureMaps referenced in different literature
 *
 * @author Max Pumperla
 * @see FeedForwardToCnn3DPreProcessor for opposite case (i.e., DenseLayer -> CNN3D)
 */
@Data
public class Cnn3DToFeedForwardPreProcessor implements InputPreProcessor {
    protected int inputDepth;
    protected int inputHeight;
    protected int inputWidth;
    protected int numChannels;
    protected boolean isNCDHW = true; // channels first ordering by default

    /**
     * @param inputDepth  input channels
     * @param inputHeight input height
     * @param inputWidth  input width
     * @param numChannels input channels
     * @param isNCDHW     boolean to indicate data format, i.e. channels first (NCDHW) vs. channels last (NDHWC)
     */
    @JsonCreator
    public Cnn3DToFeedForwardPreProcessor(@JsonProperty("inputDepth") int inputDepth,
                                          @JsonProperty("inputHeight") int inputHeight,
                                          @JsonProperty("inputWidth") int inputWidth,
                                          @JsonProperty("numChannels") int numChannels,
                                          @JsonProperty("isNCDHW") boolean isNCDHW) {
        this.inputDepth = inputDepth;
        this.inputHeight = inputHeight;
        this.inputWidth = inputWidth;
        this.numChannels = numChannels;
        this.isNCDHW = isNCDHW;
    }

    public Cnn3DToFeedForwardPreProcessor(int inputDepth, int inputHeight, int inputWidth) {
        this.inputDepth = inputDepth;
        this.inputHeight = inputHeight;
        this.inputWidth = inputWidth;
        this.numChannels = 1;
    }

    public Cnn3DToFeedForwardPreProcessor() {
    }

    @Override
    public INDArray preProcess(INDArray input, int miniBatchSize) {
        if (input.rank() == 2)
            return input; // Pass-through feed-forward input

        // We expect either NCDHW or NDHWC format
        if ((isNCDHW && input.size(1) != numChannels) || (!isNCDHW && input.size(4) != numChannels)) {
            throw new IllegalStateException("Invalid input array: expected shape in format "
                    + "[minibatch, channels, channels, height, width] or "
                    + "[minibatch, channels, height, width, channels]"
                    + "for numChannels: " + numChannels + ", inputDepth" + inputDepth + ", inputHeight" + inputHeight
                    + " and inputWidth]" + inputWidth + ", but got "
                    + Arrays.toString(input.shape()));
        }


        //Assume input is standard rank 5 activations out of CNN3D layer
        //First: we require input to be in c order. But c order (as declared in array order) isn't enough;
        // also need strides to be correct
        if (input.ordering() != 'c' || !Shape.strideDescendingCAscendingF(input))
            input = input.dup('c');

        int[] inShape = input.shape();
        int[] outShape = new int[]{inShape[0], inShape[1] * inShape[2] * inShape[3] * inShape[4]};

        return input.reshape('c', outShape);
    }

    @Override
    public INDArray backprop(INDArray epsilons, int miniBatchSize) {
        //Epsilons are 2d, with shape [miniBatchSize, outChannels*outD*outH*outW]
        if (epsilons.ordering() != 'c' || !Shape.strideDescendingCAscendingF(epsilons))
            epsilons = epsilons.dup('c');

        if (epsilons.rank() == 5)
            return epsilons;

        if (epsilons.columns() != inputDepth * inputWidth * inputHeight * numChannels)
            throw new IllegalArgumentException("Invalid input: expect output columns must be equal to rows "
                    + inputHeight + " x columns " + inputWidth + " x channels " + numChannels + " but was instead "
                    + Arrays.toString(epsilons.shape()));

        if (isNCDHW)
            return epsilons.reshape('c', epsilons.size(0), numChannels, inputDepth, inputHeight, inputWidth);
        else
            return epsilons.reshape('c', epsilons.size(0), inputDepth, inputHeight, inputWidth, numChannels);

    }

    @Override
    public Cnn3DToFeedForwardPreProcessor clone() {
        try {
            Cnn3DToFeedForwardPreProcessor clone = (Cnn3DToFeedForwardPreProcessor) super.clone();
            return clone;
        } catch (CloneNotSupportedException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public InputType getOutputType(InputType inputType) {
        if (inputType == null || inputType.getType() != InputType.Type.CNN3D) {
            throw new IllegalStateException("Invalid input type: Expected input of type CNN3D, got " + inputType);
        }

        InputType.InputTypeConvolutional3D c = (InputType.InputTypeConvolutional3D) inputType;
        int outSize = c.getChannels() * c.getDepth() * c.getHeight() * c.getWidth();
        return InputType.feedForward(outSize);
    }


    @Override
    public Pair<INDArray, MaskState> feedForwardMaskArray(INDArray maskArray, MaskState currentMaskState,
                                                          int minibatchSize) {
        //Pass-through, unmodified (assuming here that it's a 1d mask array - one value per example)
        return new Pair<>(maskArray, currentMaskState);
    }
}