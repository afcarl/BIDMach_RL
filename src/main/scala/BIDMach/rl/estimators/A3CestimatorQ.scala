package BIDMach.rl.estimators

//
// A Q-Critic Estimator for A3C. 
// First input is the current state. 
// Second input is the action taken by the policy. 
// Third input is the target value of the next state (after the action was applied). 
//

import BIDMat.{Mat,SBMat,CMat,DMat,FMat,IMat,LMat,HMat,GMat,GDMat,GIMat,GLMat,GSMat,GSDMat,SMat,SDMat}
import BIDMat.MatFunctions._
import BIDMat.SciFunctions._
import BIDMach.networks.layers._;
import BIDMach.networks._
import BIDMach._
import BIDMach.rl.algorithms._
import jcuda.jcudnn._
import jcuda.jcudnn.JCudnn._
import edu.berkeley.bid.MurmurHash3.MurmurHash3_x64_64;


class A3CestimatorQ(opts:A3CestimatorQ.Options = new A3CestimatorQ.Options) extends Estimator {
  
  var invtemp:ConstantLayer = null;
  var entropyw:ConstantLayer = null;
  var gradw:ConstantLayer = null;
  
  var preds:Layer = null;
  var probs:Layer = null;
  var entropy:Layer = null;
  var loss:Layer = null;
  var nentropy = 0;
  
  override def formatStates(s:FMat) = {
    if (net.opts.tensorFormat == Net.TensorNCHW) {
    	s.reshapeView(s.dims(2), s.dims(0), s.dims(1), s.dims(3));
    } else {
    	val x = s.transpose(2\0\1\3);
    	x.setGUID(MurmurHash3_x64_64(Array(s.GUID), "transpose213".##));
    	x;
    }
  }
    
	def createNet = {
	  import BIDMach.networks.layers.Layer._;
	  Net.initDefault(opts);

	  // Input layers 
	  val in =      input;
	  val actions = input;
	  val target =  input;
	  
	  // Settable param layers;
	  invtemp  =    const(1f);
	  entropyw =    const(1f);
	  gradw =       const(1f);

	  // Random constants
	  val minus1 =  const(-1f);
	  val eps =     const(1e-6f);

	  // Convolution layers
	  val conv1 =   conv(in)(w=7,h=7,nch=opts.nhidden,stride=4,pad=3,initv=1f,convType=opts.convType);
	  val relu1 =   relu(conv1);
	  val conv2 =   conv(relu1)(w=3,h=3,nch=opts.nhidden2,stride=2,pad=0,convType=opts.convType);
	  val relu2 =   relu(conv2);

	  // FC/reward prediction layers
	  val fc3 =     linear(relu2)(outdim=opts.nhidden3,initv=2e-2f);
	  val relu3 =   relu(fc3); 
	  val ppreds =  linear(relu3)(outdim=opts.nactions,initv=5e-2f);
	  preds =       linear(relu3)(outdim=opts.nactions,initv=5e-2f);

	  // Probability layers
	  probs =       softmax(ppreds *@ invtemp); 

	  // Entropy layers
	  val logprobs= ln(probs + eps);
	  entropy =     (logprobs dot probs) *@ minus1;
	  nentropy =    Net.defaultLayerList.length;

	  // Action loss layers
	  val diff =    target - preds(actions);
	  loss =        diff *@ diff;     
	  
	  // Policy gradient
	  val values =  probs dot preds;
	  val advtg =   target - values;
	  val pgrad =   logprobs(actions) *@ forward(advtg);

	  // Total weighted negloss, maximize this
	  val out =     loss *@ minus1 + pgrad *@ gradw + entropy *@ entropyw;

	  Net.getDefaultNet;
  }

	override val net = createNet;
	
	// Set temperature and entropy weight
  override def setConsts3(invtempv:Float, entropyWeight:Float, gradWeight:Float) = {
	  invtemp.opts.value.set(invtempv);
	  entropyw.opts.value.set(entropyWeight);
	  gradw.opts.value.set(gradWeight);
  }
  
  // Get the Q-predictions, action probabilities, entropy and loss for the last forward pass. 
  override def getOutputs4:(FMat,FMat,FMat,FMat) = {
    (FMat(preds.output),
     FMat(probs.output),
     FMat(entropy.output),
     FMat(loss.output)
    		)    
  }
};

object A3CestimatorQ {
  trait Opts extends Estimator.Opts {
    var nhidden = 16;
    var nhidden2 = 32;
    var nhidden3 = 256;
    var nactions = 3;
    tensorFormat = Net.TensorNCHW;
  }
  
  class Options extends Opts {}
}
