package BIDMach.rl.estimators

import BIDMat.{Mat,SBMat,CMat,DMat,FMat,IMat,LMat,HMat,GMat,GDMat,GIMat,GLMat,GSMat,GSDMat,SMat,SDMat}
import BIDMat.MatFunctions._
import BIDMat.SciFunctions._
import BIDMach.datasources._
import BIDMach.updaters._
import BIDMach.mixins._
import BIDMach.models._
import BIDMach._
import BIDMach.rl.algorithms._
import jcuda.jcudnn._
import jcuda.jcudnn.JCudnn._
import scala.util.hashing.MurmurHash3;
import java.util.HashMap;
import BIDMach.networks._

@SerialVersionUID(100L)
abstract class Estimator(opts:Algorithm.Options = new Algorithm.Options) extends Serializable {
    
    def formatStates(states:FMat):FMat = {states}
    
    val net:Net = null;
    
    val updater = new ADAGrad(opts);
    
/** Perform the initialization that is normally done by the Learner */

    var initialized = false;
    
    def checkinit(states:FMat, actions:IMat, rewards:FMat):Unit = {
    	if (net.mats.asInstanceOf[AnyRef] == null) {
    		net.mats = new Array[Mat](3);
    		net.gmats = new Array[Mat](3);
    	}
    	net.mats(0) = states;
    	val actionrows = if (actions.asInstanceOf[AnyRef] != null) actions.nrows else 1;
    	val rewardrows = if (rewards.asInstanceOf[AnyRef] != null) rewards.nrows else 1;
    	if (net.mats(1).asInstanceOf[AnyRef] == null) {
    		net.mats(1) = izeros(actionrows, states.ncols);              // Dummy action vector
    		net.mats(2) = zeros(rewardrows, states.ncols);               // Dummy reward vector
    	}
    	if (actions.asInstanceOf[AnyRef] != null) {
    		net.mats(1) <-- actions;
    	}
    	if (rewards.asInstanceOf[AnyRef] != null) {
    		net.mats(2) <-- rewards;
    	}
    	if (!initialized) {
    		net.useGPU = (opts.useGPU && Mat.hasCUDA > 0);
    		net.init();
    		updater.init(net);
    		initialized = true;
    	}
    	net.copyMats(net.mats, net.gmats);
    	net.assignInputs(net.gmats, 0, 0);
    	net.assignTargets(net.gmats, 0, 0);
    }
    
    def checkinit(states:FMat, actions:IMat, rewards:FMat, other:FMat):Unit = {
    	if (net.mats.asInstanceOf[AnyRef] == null) {
    		net.mats = new Array[Mat](4);
    		net.gmats = new Array[Mat](4);
    	}
    	net.mats(0) = states;
    	val actionrows = if (actions.asInstanceOf[AnyRef] != null) actions.nrows else 1;
    	val rewardrows = if (rewards.asInstanceOf[AnyRef] != null) rewards.nrows else 1;
    	val otherrows = if (other.asInstanceOf[AnyRef] != null) other.nrows else 1;
 
    	if (net.mats(1).asInstanceOf[AnyRef] == null) {
    		net.mats(1) = izeros(actionrows, states.ncols);              // Dummy action vector
    		net.mats(2) = zeros(rewardrows, states.ncols);               // Dummy reward vector
    		net.mats(3) = zeros(otherrows, states.ncols);
    	}
    	if (actions.asInstanceOf[AnyRef] != null) {
    		net.mats(1) <-- actions;
    	}
    	if (rewards.asInstanceOf[AnyRef] != null) {
    		net.mats(2) <-- rewards;
    	}
    	if (other.asInstanceOf[AnyRef] != null) {
    		net.mats(3) <-- other;
    	}
    	if (!initialized) {
    		net.useGPU = (opts.useGPU && Mat.hasCUDA > 0);
    		net.init();
    		updater.init(net);
    		initialized = true;
    	}
    	net.copyMats(net.mats, net.gmats);
    	net.assignInputs(net.gmats, 0, 0);
    	net.assignTargets(net.gmats, 0, 0);
    }
    
/**  Run the model forward given a state as input up to the action prediction layer. 
     Action selection/scoring layers are not updated.
     returns action predictions */
    def predict(states:FMat, nlayers:Int = 0) = {
    	val fstates = formatStates(states);
    	checkinit(fstates, null, null); 	
    	val nlayers0 = if (nlayers > 0) nlayers else (net.layers.length-1);
    	for (i <- 0 to nlayers0) net.layers(i).forward;
    }
    
    def predict4(states:FMat, nlayers:Int = 0) = {
    	val fstates = formatStates(states);
    	checkinit(fstates, null, null, null);
    	val nlayers0 = if (nlayers > 0) nlayers else (net.layers.length-1);
    	for (i <- 0 to nlayers0) net.layers(i).forward;
    }

/** Run the model all the way forward to the squared loss output layer, 
    and then backward to compute gradients.
    An action vector and reward vector must be given. */  

    def gradient(states:FMat, actions:IMat, rewards:FMat, ndout:Int):Unit = {
      val ndout0 = if (ndout == 0) states.ncols else ndout;
    	val fstates = formatStates(states);
    	checkinit(fstates, actions, rewards);
    	net.forward;
    	net.setderiv(ndout0);
    	net.backward(0, 0);
    }
    
    def gradient(states:FMat, actions:IMat, rewards:FMat):Unit = {
      gradient(states, actions, rewards, 0);
    }
    
    def gradient4(states:FMat, actions:IMat, rewards:FMat, rewards2:FMat, ndout:Int):Unit = {
      val ndout0 = if (ndout == 0) states.ncols else ndout;
    	val fstates = formatStates(states);
    	checkinit(fstates, actions, rewards, rewards2);
    	net.forward;
    	net.setderiv(ndout0);
    	net.backward(0, 0);
    }
    
    def gradient4(states:FMat, actions:IMat, rewards:FMat, rewards2:FMat):Unit = {
      gradient4(states, actions, rewards, rewards2, 0);
    }

        
/** MSprop, i.e. RMSprop without the square root, or natural gradient */
        
    def msprop(learning_rate:Float) = {                
    	opts.lrate = learning_rate;
    	if (learning_rate > 1e-10f) {
    		updater.update(0,0,0);		
    		net.clearUpdatemats;
    	}
    }

    def update_from(from_estimator:Estimator) = {
    	for (k  <- 0 until net.modelmats.length) {
    	  if (net.modelmats(k).asInstanceOf[AnyRef] != null && from_estimator.net.modelmats(k).asInstanceOf[AnyRef] != null) {
    	  	net.modelmats(k) <-- from_estimator.net.modelmats(k);
    	  }
    	}
    }
    
    def setConsts1(a:Float) = {}
    
    def setConsts2(a:Float, b:Float) = {}
    
    def setConsts3(a:Float, b:Float, c:Float) = {}
    
    def getOutputs2:(FMat,FMat) = {(zeros(1,1), zeros(1,1))}
        
    def getOutputs3:(FMat,FMat,FMat) = {(zeros(1,1), zeros(1,1), zeros(1,1))}
    
    def getOutputs4:(FMat,FMat,FMat,FMat) = {(zeros(1,1), zeros(1,1), zeros(1,1), zeros(1,1))}
};

object Estimator {
  trait Opts extends Net.Opts with ADAGrad.Opts {
    
  }
  
  trait Options extends Opts {}
}
