package org.deeplearning4j.gradientcheck;

import org.deeplearning4j.datasets.iterator.impl.IrisDataSetIterator;
import org.deeplearning4j.nn.api.OptimizationAlgorithm;
import org.deeplearning4j.nn.conf.ConvolutionMode;
import org.deeplearning4j.nn.conf.MultiLayerConfiguration;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.conf.distribution.NormalDistribution;
import org.deeplearning4j.nn.conf.inputs.InputType;
import org.deeplearning4j.nn.conf.layers.*;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.deeplearning4j.nn.weights.WeightInit;
import org.junit.Test;
import org.nd4j.linalg.activations.Activation;
import org.nd4j.linalg.api.buffer.DataBuffer;
import org.nd4j.linalg.api.buffer.util.DataTypeUtil;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.dataset.DataSet;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.learning.config.NoOp;
import org.nd4j.linalg.lossfunctions.LossFunctions;

import java.util.Arrays;

import static org.junit.Assert.*;

/**
 * Created by nyghtowl on 9/1/15.
 */
public class CNNGradientCheckTest {
    private static final boolean PRINT_RESULTS = true;
    private static final boolean RETURN_ON_FIRST_FAILURE = false;
    private static final double DEFAULT_EPS = 1e-6;
    private static final double DEFAULT_MAX_REL_ERROR = 1e-3;
    private static final double DEFAULT_MIN_ABS_ERROR = 1e-8;

    static {
        DataTypeUtil.setDTypeForContext(DataBuffer.Type.DOUBLE);
    }

    @Test
    public void testGradientCNNMLN() {
        //Parameterized test, testing combinations of:
        // (a) activation function
        // (b) Whether to test at random initialization, or after some learning (i.e., 'characteristic mode of operation')
        // (c) Loss function (with specified output activations)
        Activation[] activFns = {Activation.SIGMOID, Activation.TANH};
        boolean[] characteristic = {false, true}; //If true: run some backprop steps first

        LossFunctions.LossFunction[] lossFunctions =
                        {LossFunctions.LossFunction.NEGATIVELOGLIKELIHOOD, LossFunctions.LossFunction.MSE};
        Activation[] outputActivations = {Activation.SOFTMAX, Activation.TANH}; //i.e., lossFunctions[i] used with outputActivations[i] here

        DataSet ds = new IrisDataSetIterator(150, 150).next();
        ds.normalizeZeroMeanZeroUnitVariance();
        INDArray input = ds.getFeatureMatrix();
        INDArray labels = ds.getLabels();

        for (Activation afn : activFns) {
            for (boolean doLearningFirst : characteristic) {
                for (int i = 0; i < lossFunctions.length; i++) {
                    LossFunctions.LossFunction lf = lossFunctions[i];
                    Activation outputActivation = outputActivations[i];

                    MultiLayerConfiguration.Builder builder = new NeuralNetConfiguration.Builder()
                                    .optimizationAlgo(OptimizationAlgorithm.CONJUGATE_GRADIENT).updater(new NoOp())
                                    .weightInit(WeightInit.XAVIER).seed(12345L).list()
                                    .layer(0, new ConvolutionLayer.Builder(1, 1).nOut(6).activation(afn).build())
                                    .layer(1, new OutputLayer.Builder(lf).activation(outputActivation).nOut(3).build())
                                    .setInputType(InputType.convolutionalFlat(1, 4, 1)).pretrain(false).backprop(true);

                    MultiLayerConfiguration conf = builder.build();

                    MultiLayerNetwork mln = new MultiLayerNetwork(conf);
                    mln.init();
                    String name = new Object() {}.getClass().getEnclosingMethod().getName();

                    if (doLearningFirst) {
                        //Run a number of iterations of learning
                        mln.setInput(ds.getFeatures());
                        mln.setLabels(ds.getLabels());
                        mln.computeGradientAndScore();
                        double scoreBefore = mln.score();
                        for (int j = 0; j < 10; j++)
                            mln.fit(ds);
                        mln.computeGradientAndScore();
                        double scoreAfter = mln.score();
                        //Can't test in 'characteristic mode of operation' if not learning
                        String msg = name + " - score did not (sufficiently) decrease during learning - activationFn="
                                        + afn + ", lossFn=" + lf + ", outputActivation=" + outputActivation
                                        + ", doLearningFirst= " + doLearningFirst + " (before=" + scoreBefore
                                        + ", scoreAfter=" + scoreAfter + ")";
                        assertTrue(msg, scoreAfter < 0.8 * scoreBefore);
                    }

                    if (PRINT_RESULTS) {
                        System.out.println(name + " - activationFn=" + afn + ", lossFn=" + lf + ", outputActivation="
                                        + outputActivation + ", doLearningFirst=" + doLearningFirst);
                        for (int j = 0; j < mln.getnLayers(); j++)
                            System.out.println("Layer " + j + " # params: " + mln.getLayer(j).numParams());
                    }

                    boolean gradOK = GradientCheckUtil.checkGradients(mln, DEFAULT_EPS, DEFAULT_MAX_REL_ERROR,
                                    DEFAULT_MIN_ABS_ERROR, PRINT_RESULTS, RETURN_ON_FIRST_FAILURE, input, labels);

                    assertTrue(gradOK);
                }
            }
        }
    }



    @Test
    public void testGradientCNNL1L2MLN() {
        //Parameterized test, testing combinations of:
        // (a) activation function
        // (b) Whether to test at random initialization, or after some learning (i.e., 'characteristic mode of operation')
        // (c) Loss function (with specified output activations)
        Activation[] activFns = {Activation.SIGMOID, Activation.TANH};
        boolean[] characteristic = {false, true}; //If true: run some backprop steps first

        LossFunctions.LossFunction[] lossFunctions =
                        {LossFunctions.LossFunction.NEGATIVELOGLIKELIHOOD, LossFunctions.LossFunction.MSE};
        Activation[] outputActivations = {Activation.SOFTMAX, Activation.TANH}; //i.e., lossFunctions[i] used with outputActivations[i] here

        DataSet ds = new IrisDataSetIterator(150, 150).next();
        ds.normalizeZeroMeanZeroUnitVariance();
        INDArray input = ds.getFeatureMatrix();
        INDArray labels = ds.getLabels();

        //use l2vals[i] with l1vals[i]
        double[] l2vals = {0.4, 0.0, 0.4, 0.4};
        double[] l1vals = {0.0, 0.0, 0.5, 0.0};
        double[] biasL2 = {0.0, 0.0, 0.0, 0.2};
        double[] biasL1 = {0.0, 0.0, 0.6, 0.0};

        for (Activation afn : activFns) {
            for (boolean doLearningFirst : characteristic) {
                for (int i = 0; i < lossFunctions.length; i++) {
                    for (int k = 0; k < l2vals.length; k++) {
                        LossFunctions.LossFunction lf = lossFunctions[i];
                        Activation outputActivation = outputActivations[i];
                        double l2 = l2vals[k];
                        double l1 = l1vals[k];

                        MultiLayerConfiguration.Builder builder = new NeuralNetConfiguration.Builder()
                                        .l2(l2).l1(l1).l2Bias(biasL2[k]).l1Bias(biasL1[k])
                                        .optimizationAlgo(
                                                        OptimizationAlgorithm.CONJUGATE_GRADIENT)
                                        .seed(12345L).list()
                                        .layer(0, new ConvolutionLayer.Builder(new int[] {1, 1}).nIn(1).nOut(6)
                                                        .weightInit(WeightInit.XAVIER).activation(afn)
                                                        .updater(new NoOp()).build())
                                        .layer(1, new OutputLayer.Builder(lf).activation(outputActivation).nOut(3)
                                                        .weightInit(WeightInit.XAVIER).updater(new NoOp()).build())
                                        .pretrain(false).backprop(true)
                                        .setInputType(InputType.convolutionalFlat(1, 4, 1));

                        MultiLayerConfiguration conf = builder.build();

                        MultiLayerNetwork mln = new MultiLayerNetwork(conf);
                        mln.init();
                        String testName = new Object() {}.getClass().getEnclosingMethod().getName();

                        if (doLearningFirst) {
                            //Run a number of iterations of learning
                            mln.setInput(ds.getFeatures());
                            mln.setLabels(ds.getLabels());
                            mln.computeGradientAndScore();
                            double scoreBefore = mln.score();
                            for (int j = 0; j < 10; j++)
                                mln.fit(ds);
                            mln.computeGradientAndScore();
                            double scoreAfter = mln.score();
                            //Can't test in 'characteristic mode of operation' if not learning
                            String msg = testName
                                            + "- score did not (sufficiently) decrease during learning - activationFn="
                                            + afn + ", lossFn=" + lf + ", outputActivation=" + outputActivation
                                            + ", doLearningFirst=" + doLearningFirst + " (before=" + scoreBefore
                                            + ", scoreAfter=" + scoreAfter + ")";
                            assertTrue(msg, scoreAfter < 0.8 * scoreBefore);
                        }

                        if (PRINT_RESULTS) {
                            System.out.println(testName + "- activationFn=" + afn + ", lossFn=" + lf
                                            + ", outputActivation=" + outputActivation + ", doLearningFirst="
                                            + doLearningFirst);
                            for (int j = 0; j < mln.getnLayers(); j++)
                                System.out.println("Layer " + j + " # params: " + mln.getLayer(j).numParams());
                        }

                        boolean gradOK = GradientCheckUtil.checkGradients(mln, DEFAULT_EPS, DEFAULT_MAX_REL_ERROR,
                                        DEFAULT_MIN_ABS_ERROR, PRINT_RESULTS, RETURN_ON_FIRST_FAILURE, input, labels);

                        assertTrue(gradOK);
                    }
                }
            }
        }
    }

    @Test
    public void testCnnWithUpsampling() {
        Nd4j.getRandom().setSeed(12345);
        int nOut = 4;

        int[] minibatchSizes = {1, 3};
        int width = 5;
        int height = 5;
        int inputDepth = 1;

        int[] kernel = {2, 2};
        int[] stride = {1, 1};
        int[] padding = {0, 0};
        int size = 2;

        String[] activations = {"sigmoid", "tanh"};
        SubsamplingLayer.PoolingType[] poolingTypes =
                new SubsamplingLayer.PoolingType[] {SubsamplingLayer.PoolingType.MAX,
                        SubsamplingLayer.PoolingType.AVG, SubsamplingLayer.PoolingType.PNORM};

        for (String afn : activations) {
            for (SubsamplingLayer.PoolingType poolingType : poolingTypes) {
                for (int minibatchSize : minibatchSizes) {
                    INDArray input = Nd4j.rand(minibatchSize, width * height * inputDepth);
                    INDArray labels = Nd4j.zeros(minibatchSize, nOut);
                    for (int i = 0; i < minibatchSize; i++) {
                        labels.putScalar(new int[] {i, i % nOut}, 1.0);
                    }

                    MultiLayerConfiguration conf =
                            new NeuralNetConfiguration.Builder().regularization(false).learningRate(1.0)
                                    .updater(Updater.SGD).weightInit(WeightInit.DISTRIBUTION)
                                    .dist(new NormalDistribution(0, 1))
                                    .list().layer(new ConvolutionLayer.Builder(kernel,
                                            stride, padding).nIn(inputDepth)
                                            .nOut(3).build())//output: (5-2+0)/1+1 = 4
                                    .layer(new Upsampling2D.Builder().size(size).build()) //output: 4*2 =8 -> 8x8x3
                                    .layer(new OutputLayer.Builder(LossFunctions.LossFunction.MCXENT)
                                            .activation(Activation.SOFTMAX).nIn(8 * 8 * 3)
                                            .nOut(4).build())
                                    .setInputType(InputType.convolutionalFlat(height, width,
                                            inputDepth))
                                    .build();

                    MultiLayerNetwork net = new MultiLayerNetwork(conf);
                    net.init();

                    String msg = "PoolingType=" + poolingType + ", minibatch=" + minibatchSize + ", activationFn="
                            + afn;

                    if (PRINT_RESULTS) {
                        System.out.println(msg);
                        for (int j = 0; j < net.getnLayers(); j++)
                            System.out.println("Layer " + j + " # params: " + net.getLayer(j).numParams());
                    }

                    boolean gradOK = GradientCheckUtil.checkGradients(net, DEFAULT_EPS, DEFAULT_MAX_REL_ERROR,
                            DEFAULT_MIN_ABS_ERROR, PRINT_RESULTS, RETURN_ON_FIRST_FAILURE, input, labels);

                    assertTrue(msg, gradOK);
                }
            }
        }
    }


    @Test
    public void testCnnWithSubsampling() {
        Nd4j.getRandom().setSeed(12345);
        int nOut = 4;

        int[] minibatchSizes = {1, 3};
        int width = 5;
        int height = 5;
        int inputDepth = 1;

        int[] kernel = {2, 2};
        int[] stride = {1, 1};
        int[] padding = {0, 0};
        int pnorm = 2;

        String[] activations = {"sigmoid", "tanh"};
        SubsamplingLayer.PoolingType[] poolingTypes =
                        new SubsamplingLayer.PoolingType[] {SubsamplingLayer.PoolingType.MAX,
                                        SubsamplingLayer.PoolingType.AVG, SubsamplingLayer.PoolingType.PNORM};

        for (String afn : activations) {
            for (SubsamplingLayer.PoolingType poolingType : poolingTypes) {
                for (int minibatchSize : minibatchSizes) {
                    INDArray input = Nd4j.rand(minibatchSize, width * height * inputDepth);
                    INDArray labels = Nd4j.zeros(minibatchSize, nOut);
                    for (int i = 0; i < minibatchSize; i++) {
                        labels.putScalar(new int[] {i, i % nOut}, 1.0);
                    }

                    MultiLayerConfiguration conf =
                                    new NeuralNetConfiguration.Builder().updater(new NoOp())
                                                    .weightInit(WeightInit.DISTRIBUTION)
                                                    .dist(new NormalDistribution(0, 1))
                                                    .list().layer(0,
                                                                    new ConvolutionLayer.Builder(kernel,
                                                                                    stride, padding).nIn(inputDepth)
                                                                                                    .nOut(3).build())//output: (5-2+0)/1+1 = 4
                                                    .layer(1, new SubsamplingLayer.Builder(poolingType)
                                                                    .kernelSize(kernel).stride(stride).padding(padding)
                                                                    .pnorm(pnorm).build()) //output: (4-2+0)/1+1 =3 -> 3x3x3
                                                    .layer(2, new OutputLayer.Builder(LossFunctions.LossFunction.MCXENT)
                                                                    .activation(Activation.SOFTMAX).nIn(3 * 3 * 3)
                                                                    .nOut(4).build())
                                                    .setInputType(InputType.convolutionalFlat(height, width,
                                                                    inputDepth))
                                                    .build();

                    MultiLayerNetwork net = new MultiLayerNetwork(conf);
                    net.init();

                    String msg = "PoolingType=" + poolingType + ", minibatch=" + minibatchSize + ", activationFn="
                                    + afn;

                    if (PRINT_RESULTS) {
                        System.out.println(msg);
                        for (int j = 0; j < net.getnLayers(); j++)
                            System.out.println("Layer " + j + " # params: " + net.getLayer(j).numParams());
                    }

                    boolean gradOK = GradientCheckUtil.checkGradients(net, DEFAULT_EPS, DEFAULT_MAX_REL_ERROR,
                                    DEFAULT_MIN_ABS_ERROR, PRINT_RESULTS, RETURN_ON_FIRST_FAILURE, input, labels);

                    assertTrue(msg, gradOK);
                }
            }
        }
    }

    @Test
    public void testCnnWithSubsamplingV2() {
        int nOut = 4;

        int[] minibatchSizes = {1, 3};
        int width = 5;
        int height = 5;
        int inputDepth = 1;

        int[] kernel = {2, 2};
        int[] stride = {1, 1};
        int[] padding = {0, 0};
        int pNorm = 3;

        String[] activations = {"sigmoid", "tanh"};
        SubsamplingLayer.PoolingType[] poolingTypes =
                        new SubsamplingLayer.PoolingType[] {SubsamplingLayer.PoolingType.MAX,
                                        SubsamplingLayer.PoolingType.AVG, SubsamplingLayer.PoolingType.PNORM};

        for (String afn : activations) {
            for (SubsamplingLayer.PoolingType poolingType : poolingTypes) {
                for (int minibatchSize : minibatchSizes) {
                    INDArray input = Nd4j.rand(minibatchSize, width * height * inputDepth);
                    INDArray labels = Nd4j.zeros(minibatchSize, nOut);
                    for (int i = 0; i < minibatchSize; i++) {
                        labels.putScalar(new int[] {i, i % nOut}, 1.0);
                    }

                    MultiLayerConfiguration conf =
                                    new NeuralNetConfiguration.Builder().updater(new NoOp()).weightInit(WeightInit.DISTRIBUTION)
                                                    .dist(new NormalDistribution(0, 1))
                                                    .list().layer(0,
                                                                    new ConvolutionLayer.Builder(kernel,
                                                                                    stride, padding).nIn(inputDepth)
                                                                                                    .nOut(3).build())//output: (5-2+0)/1+1 = 4
                                                    .layer(1, new SubsamplingLayer.Builder(poolingType)
                                                                    .kernelSize(kernel).stride(stride).padding(padding)
                                                                    .pnorm(pNorm).build()) //output: (4-2+0)/1+1 =3 -> 3x3x3
                                                    .layer(2, new ConvolutionLayer.Builder(kernel, stride, padding)
                                                                    .nIn(3).nOut(2).build()) //Output: (3-2+0)/1+1 = 2
                                                    .layer(3, new OutputLayer.Builder(LossFunctions.LossFunction.MCXENT)
                                                                    .activation(Activation.SOFTMAX).nIn(2 * 2 * 2)
                                                                    .nOut(4).build())
                                                    .setInputType(InputType.convolutionalFlat(height, width,
                                                                    inputDepth))
                                                    .build();

                    MultiLayerNetwork net = new MultiLayerNetwork(conf);
                    net.init();

                    String msg = "PoolingType=" + poolingType + ", minibatch=" + minibatchSize + ", activationFn="
                                    + afn;
                    System.out.println(msg);

                    boolean gradOK = GradientCheckUtil.checkGradients(net, DEFAULT_EPS, DEFAULT_MAX_REL_ERROR,
                                    DEFAULT_MIN_ABS_ERROR, PRINT_RESULTS, RETURN_ON_FIRST_FAILURE, input, labels);

                    assertTrue(msg, gradOK);
                }
            }
        }
    }

    @Test
    public void testCnnMultiLayer() {
        int nOut = 2;

        int[] minibatchSizes = {1, 2, 5};
        int width = 5;
        int height = 5;
        int[] inputDepths = {1, 2, 4};

        String[] activations = {"sigmoid", "tanh"};
        SubsamplingLayer.PoolingType[] poolingTypes = new SubsamplingLayer.PoolingType[] {
                        SubsamplingLayer.PoolingType.MAX, SubsamplingLayer.PoolingType.AVG};

        Nd4j.getRandom().setSeed(12345);

        for (int inputDepth : inputDepths) {
            for (String afn : activations) {
                for (SubsamplingLayer.PoolingType poolingType : poolingTypes) {
                    for (int minibatchSize : minibatchSizes) {
                        INDArray input = Nd4j.rand(minibatchSize, width * height * inputDepth);
                        INDArray labels = Nd4j.zeros(minibatchSize, nOut);
                        for (int i = 0; i < minibatchSize; i++) {
                            labels.putScalar(new int[] {i, i % nOut}, 1.0);
                        }

                        MultiLayerConfiguration conf = new NeuralNetConfiguration.Builder().seed(12345).updater(new NoOp())
                                        .activation(afn)
                                        .list()
                                        .layer(0, new ConvolutionLayer.Builder().kernelSize(2, 2).stride(1, 1)
                                                        .padding(0, 0).nIn(inputDepth).nOut(2).build())//output: (5-2+0)/1+1 = 4
                                        .layer(1, new ConvolutionLayer.Builder().nIn(2).nOut(2).kernelSize(2, 2)
                                                        .stride(1, 1).padding(0, 0).build()) //(4-2+0)/1+1 = 3
                                        .layer(2, new ConvolutionLayer.Builder().nIn(2).nOut(2).kernelSize(2, 2)
                                                        .stride(1, 1).padding(0, 0).build()) //(3-2+0)/1+1 = 2
                                        .layer(3, new OutputLayer.Builder(LossFunctions.LossFunction.MCXENT)
                                                        .activation(Activation.SOFTMAX).nIn(2 * 2 * 2).nOut(nOut)
                                                        .build())
                                        .setInputType(InputType.convolutionalFlat(height, width, inputDepth)).build();

                        assertEquals(ConvolutionMode.Truncate,
                                        ((ConvolutionLayer) conf.getConf(0).getLayer()).getConvolutionMode());

                        MultiLayerNetwork net = new MultiLayerNetwork(conf);
                        net.init();

                        for (int i = 0; i < 4; i++) {
                            System.out.println("nParams, layer " + i + ": " + net.getLayer(i).numParams());
                        }

                        String msg = "PoolingType=" + poolingType + ", minibatch=" + minibatchSize + ", activationFn="
                                        + afn;
                        System.out.println(msg);

                        boolean gradOK = GradientCheckUtil.checkGradients(net, DEFAULT_EPS, DEFAULT_MAX_REL_ERROR,
                                        DEFAULT_MIN_ABS_ERROR, PRINT_RESULTS, RETURN_ON_FIRST_FAILURE, input, labels);

                        assertTrue(msg, gradOK);
                    }
                }
            }
        }
    }


    @Test
    public void testCnnSamePaddingMode() {
        int nOut = 2;

        int[] minibatchSizes = {1, 3};
        int width = 5;
        int[] heights = new int[] {4, 5, 6}; //Same padding mode: insensitive to exact input size...
        int[] kernelSizes = new int[] {2, 3};
        int[] inputDepths = {1, 2, 4};

        Nd4j.getRandom().setSeed(12345);

        for (int inputDepth : inputDepths) {
            for (int minibatchSize : minibatchSizes) {
                for (int height : heights) {
                    for (int k : kernelSizes) {

                        INDArray input = Nd4j.rand(minibatchSize, width * height * inputDepth);
                        INDArray labels = Nd4j.zeros(minibatchSize, nOut);
                        for (int i = 0; i < minibatchSize; i++) {
                            labels.putScalar(new int[] {i, i % nOut}, 1.0);
                        }

                        MultiLayerConfiguration conf = new NeuralNetConfiguration.Builder().seed(12345)
                                        .updater(new NoOp())
                                        .activation(Activation.TANH).convolutionMode(ConvolutionMode.Same).list()
                                        .layer(0, new ConvolutionLayer.Builder().name("layer 0").kernelSize(k, k)
                                                        .stride(1, 1).padding(0, 0).nIn(inputDepth).nOut(2).build())
                                        .layer(1, new SubsamplingLayer.Builder()
                                                        .poolingType(SubsamplingLayer.PoolingType.MAX).kernelSize(k, k)
                                                        .stride(1, 1).padding(0, 0).build())
                                        .layer(2, new ConvolutionLayer.Builder().nIn(2).nOut(2).kernelSize(k, k)
                                                        .stride(1, 1).padding(0, 0).build())
                                        .layer(3, new OutputLayer.Builder(LossFunctions.LossFunction.MCXENT)
                                                        .activation(Activation.SOFTMAX).nOut(nOut).build())
                                        .setInputType(InputType.convolutionalFlat(height, width, inputDepth)).build();

                        MultiLayerNetwork net = new MultiLayerNetwork(conf);
                        net.init();

                        for (int i = 0; i < net.getLayers().length; i++) {
                            System.out.println("nParams, layer " + i + ": " + net.getLayer(i).numParams());
                        }

                        String msg = "Minibatch=" + minibatchSize + ", inDepth=" + inputDepth + ", height=" + height
                                        + ", kernelSize=" + k;
                        System.out.println(msg);

                        boolean gradOK = GradientCheckUtil.checkGradients(net, DEFAULT_EPS, DEFAULT_MAX_REL_ERROR,
                                        DEFAULT_MIN_ABS_ERROR, PRINT_RESULTS, RETURN_ON_FIRST_FAILURE, input, labels);

                        assertTrue(msg, gradOK);
                    }
                }
            }
        }
    }

    @Test
    public void testCnnSamePaddingModeStrided() {
        int nOut = 2;

        int[] minibatchSizes = {1, 3};
        int width = 16;
        int height = 16;
        int[] kernelSizes = new int[] {2, 3};
        int[] strides = {1, 2, 3};
        int[] inputDepths = {1, 3};

        Nd4j.getRandom().setSeed(12345);

        for (int inputDepth : inputDepths) {
            for (int minibatchSize : minibatchSizes) {
                for (int stride : strides) {
                    for (int k : kernelSizes) {
                        for (boolean convFirst : new boolean[] {true, false}) {

                            INDArray input = Nd4j.rand(minibatchSize, width * height * inputDepth);
                            INDArray labels = Nd4j.zeros(minibatchSize, nOut);
                            for (int i = 0; i < minibatchSize; i++) {
                                labels.putScalar(new int[] {i, i % nOut}, 1.0);
                            }

                            Layer convLayer = new ConvolutionLayer.Builder().name("layer 0").kernelSize(k, k)
                                            .stride(stride, stride).padding(0, 0).nIn(inputDepth).nOut(2).build();

                            Layer poolLayer = new SubsamplingLayer.Builder()
                                            .poolingType(SubsamplingLayer.PoolingType.MAX).kernelSize(k, k)
                                            .stride(stride, stride).padding(0, 0).build();

                            MultiLayerConfiguration conf = new NeuralNetConfiguration.Builder().seed(12345)
                                            .updater(new NoOp())
                                            .activation(Activation.TANH).convolutionMode(ConvolutionMode.Same).list()
                                            .layer(0, convFirst ? convLayer : poolLayer)
                                            .layer(1, convFirst ? poolLayer : convLayer)
                                            .layer(2, new OutputLayer.Builder(LossFunctions.LossFunction.MCXENT)
                                                            .activation(Activation.SOFTMAX).nOut(nOut).build())
                                            .setInputType(InputType.convolutionalFlat(height, width, inputDepth))
                                            .build();

                            MultiLayerNetwork net = new MultiLayerNetwork(conf);
                            net.init();

                            for (int i = 0; i < net.getLayers().length; i++) {
                                System.out.println("nParams, layer " + i + ": " + net.getLayer(i).numParams());
                            }

                            String msg = "Minibatch=" + minibatchSize + ", inDepth=" + inputDepth + ", height=" + height
                                            + ", kernelSize=" + k + ", stride = " + stride + ", convLayer first = "
                                            + convFirst;
                            System.out.println(msg);

                            boolean gradOK = GradientCheckUtil.checkGradients(net, DEFAULT_EPS, DEFAULT_MAX_REL_ERROR,
                                            DEFAULT_MIN_ABS_ERROR, PRINT_RESULTS, RETURN_ON_FIRST_FAILURE, input,
                                            labels);

                            assertTrue(msg, gradOK);
                        }
                    }
                }
            }
        }
    }


    @Test
    public void testCnnZeroPaddingLayer() {
        Nd4j.getRandom().setSeed(12345);
        int nOut = 4;

        int[] minibatchSizes = {1, 3};
        int width = 6;
        int height = 6;
        int[] inputDepths = {1, 3};

        int[] kernel = {2, 2};
        int[] stride = {1, 1};
        int[] padding = {0, 0};

        int[][] zeroPadLayer = new int[][] {{0, 0, 0, 0}, {1, 1, 0, 0}, {2, 2, 2, 2}};

        for (int inputDepth : inputDepths) {
            for (int minibatchSize : minibatchSizes) {
                INDArray input = Nd4j.rand(new int[] {minibatchSize, inputDepth, height, width});
                INDArray labels = Nd4j.zeros(minibatchSize, nOut);
                for (int i = 0; i < minibatchSize; i++) {
                    labels.putScalar(new int[] {i, i % nOut}, 1.0);
                }
                for (int[] zeroPad : zeroPadLayer) {

                    MultiLayerConfiguration conf =
                                    new NeuralNetConfiguration.Builder().updater(new NoOp()).weightInit(WeightInit.DISTRIBUTION)
                                                    .dist(new NormalDistribution(0, 1)).list()
                                                    .layer(0, new ConvolutionLayer.Builder(kernel, stride, padding)
                                                                    .nIn(inputDepth).nOut(3).build())//output: (6-2+0)/1+1 = 5
                                                    .layer(1, new ZeroPaddingLayer.Builder(zeroPad).build()).layer(2,
                                                                    new ConvolutionLayer.Builder(kernel, stride,
                                                                                    padding).nIn(3).nOut(3).build())//output: (6-2+0)/1+1 = 5
                                                    .layer(3, new OutputLayer.Builder(LossFunctions.LossFunction.MCXENT)
                                                                    .activation(Activation.SOFTMAX).nOut(4).build())
                                                    .setInputType(InputType.convolutional(height, width, inputDepth))
                                                    .build();

                    MultiLayerNetwork net = new MultiLayerNetwork(conf);
                    net.init();

                    //Check zero padding activation shape
                    org.deeplearning4j.nn.layers.convolution.ZeroPaddingLayer zpl =
                                    (org.deeplearning4j.nn.layers.convolution.ZeroPaddingLayer) net.getLayer(1);
                    int[] expShape = new int[] {minibatchSize, inputDepth, height + zeroPad[0] + zeroPad[1],
                                    width + zeroPad[2] + zeroPad[3]};
                    INDArray out = zpl.activate(input);
                    assertArrayEquals(expShape, out.shape());

                    String msg = "minibatch=" + minibatchSize + ", depth=" + inputDepth + ", zeroPad = "
                                    + Arrays.toString(zeroPad);

                    if (PRINT_RESULTS) {
                        System.out.println(msg);
                        for (int j = 0; j < net.getnLayers(); j++)
                            System.out.println("Layer " + j + " # params: " + net.getLayer(j).numParams());
                    }

                    boolean gradOK = GradientCheckUtil.checkGradients(net, DEFAULT_EPS, DEFAULT_MAX_REL_ERROR,
                                    DEFAULT_MIN_ABS_ERROR, PRINT_RESULTS, RETURN_ON_FIRST_FAILURE, input, labels);

                    assertTrue(msg, gradOK);
                }
            }
        }
    }


    @Test
    public void testCnnDilated() {
        int nOut = 2;

        int minibatchSize = 3;
        int width = 8;
        int height = 8;
        int inputDepth = 3;
        int[] kernelSizes = new int[]{2, 3};
        int[] strides = {1, 2};
        int[] dilation = {2, 3};
        ConvolutionMode[] cModes = new ConvolutionMode[]{ConvolutionMode.Truncate, ConvolutionMode.Same};

        Nd4j.getRandom().setSeed(12345);

        for (boolean subsampling : new boolean[]{false, true}) {
            for (int k : kernelSizes) {
                for (int s : strides) {
                    for (int d : dilation) {
                        for (ConvolutionMode cm : cModes) {

                            //Use larger input with larger dilation values (to avoid invalid config)
                            int w = d * width;
                            int h = d * height;

                            INDArray input = Nd4j.rand(minibatchSize, w * h * inputDepth);
                            INDArray labels = Nd4j.zeros(minibatchSize, nOut);
                            for (int i = 0; i < minibatchSize; i++) {
                                labels.putScalar(new int[]{i, i % nOut}, 1.0);
                            }

                            NeuralNetConfiguration.ListBuilder b = new NeuralNetConfiguration.Builder().seed(12345)
                                    .updater(new NoOp())
                                    .activation(Activation.TANH).convolutionMode(cm).list()
                                    .layer(new ConvolutionLayer.Builder().name("layer 0")
                                            .kernelSize(k, k)
                                            .stride(s, s)
                                            .dilation(d, d)
                                            .nIn(inputDepth).nOut(2).build());
                            if (subsampling) {
                                b.layer(new SubsamplingLayer.Builder()
                                        .poolingType(SubsamplingLayer.PoolingType.MAX)
                                        .kernelSize(k, k)
                                        .stride(s, s)
                                        .dilation(d, d)
                                        .build());
                            } else {
                                b.layer(new ConvolutionLayer.Builder().nIn(2).nOut(2)
                                        .kernelSize(k, k)
                                        .stride(s, s)
                                        .dilation(d, d)
                                        .build());
                            }

                            MultiLayerConfiguration conf = b.layer(new OutputLayer.Builder(LossFunctions.LossFunction.MCXENT)
                                    .activation(Activation.SOFTMAX).nOut(nOut).build())
                                    .setInputType(InputType.convolutionalFlat(h, w, inputDepth)).build();

                            MultiLayerNetwork net = new MultiLayerNetwork(conf);
                            net.init();

                            for (int i = 0; i < net.getLayers().length; i++) {
                                System.out.println("nParams, layer " + i + ": " + net.getLayer(i).numParams());
                            }

                            String msg = (subsampling ? "subsampling" : "conv") + " - mb=" + minibatchSize + ", k="
                                    + k + ", s=" + s + ", d=" + d + ", cm=" + cm;
                            System.out.println(msg);

                            boolean gradOK = GradientCheckUtil.checkGradients(net, DEFAULT_EPS, DEFAULT_MAX_REL_ERROR,
                                    DEFAULT_MIN_ABS_ERROR, PRINT_RESULTS, RETURN_ON_FIRST_FAILURE, input, labels);

                            assertTrue(msg, gradOK);
                        }
                    }
                }
            }
        }
    }
}
