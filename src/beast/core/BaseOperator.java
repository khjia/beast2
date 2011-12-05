
/*
 * File Operator.java
 *
 * Copyright (C) 2011 BEAST2 Core Team
 *
 * This file is part of BEAST2.
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership and licensing.
 *
 * BEAST is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 *  BEAST is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with BEAST; if not, write to the
 * Free Software Foundation, Inc., 51 Franklin St, Fifth Floor,
 * Boston, MA  02110-1301  USA
 */
package beast.core;

import java.text.DecimalFormat;

import beast.core.Input.Validate;


@Description("Proposes a move in state space.")
public abstract class BaseOperator extends Plugin {
	public Input<Double> m_pWeight = new Input<Double>("weight","weight with which this operator is selected", Validate.REQUIRED);

    /** Implement this for proposing new states based on evaluations of
     * a distribution.
     *
     * @return a distribution or null if not required
     **/
    abstract public Distribution getEvaluatorDistribution();

	/** Implement this for proposing a new State.
	 * The proposal is responsible for keeping the State valid,
	 * and if the State becomes invalid (e.g. a parameter goes out
	 * of its range) Double.NEGATIVE_INFINITY should be returned.
	 *
	 * If the operator is a Gibbs operator, hence the proposal should
	 * always be accepted, the method should return Double.POSITIVE_INFINITY.
	 *
     * @param evaluator An evaluator object that can be use to repetitively
     * used to evaluate the distributribution returned by getEvaluatorDistribution().
     *
	 * @return log of Hastings Ratio, or Double.NEGATIVE_INFINITY if proposal
	 * should not be accepted (because the proposal is invalid) or
	 * Double.POSITIVE_INFINITY if the proposal should always be accepted
	 * (for Gibbs operators).
     **/
	abstract public double proposal(Evaluator evaluator);

	/**
     * @return the relative weight which determines the probability this proposal is chosen
	 * from among all the available proposals
	 */
	public double getWeight() {
		return m_pWeight.get();
	}

	public String getName() {
        return this.getClass().getName() + (getID()!=null?"_" +getID():"");
    }


	/** keep statistics of how often this operator was used, accepted or rejected **/
	protected int m_nNrRejected = 0;
	protected int m_nNrAccepted = 0;
	int m_nNrRejectedForCorrection = 0;
	int m_nNrAcceptedForCorrection = 0;
	public void accept() {
		m_nNrAccepted++;
		if (g_autoOptimizeDelay >= AUTO_OPTIMIZE_DELAY) {
			m_nNrAcceptedForCorrection++;
		}
	}

	public void reject() {
		m_nNrRejected++;
		if (g_autoOptimizeDelay >= AUTO_OPTIMIZE_DELAY) {
			m_nNrRejectedForCorrection++;
		}
	}

	/** called after every invocation of this operator to see whether
	 * a parameter can be optimised for better acceptance hence faster
	 * mixing
	 * @param logAlpha difference in posterior between previous state & proposed state + hasting ratio
	 */
	public void optimize(double logAlpha) {
		// must be overridden by operator implementation to have an effect
	}

	/** @return  change of value of a parameter for MCMC chain optimisation
     * @param logAlpha difference in posterior between previous state & proposed state + hasting ratio
     **/
	final static protected int AUTO_OPTIMIZE_DELAY = 10000;
	static protected int g_autoOptimizeDelay = 0;
	protected double calcDelta(double logAlpha) {
		// do no optimisation for the first N optimisable operations
		if (g_autoOptimizeDelay < AUTO_OPTIMIZE_DELAY) {
			g_autoOptimizeDelay++;
			return 0;
		}
        final double target = getTargetAcceptanceProbability();

        final double deltaP = ((1.0 / (m_nNrRejectedForCorrection + m_nNrAcceptedForCorrection + 1.0)) * (Math.exp(Math.min(logAlpha, 0)) - target));

        if (deltaP > -Double.MAX_VALUE && deltaP < Double.MAX_VALUE) {
            return deltaP;
        }
        return 0;
	} // calcDelta

	/** @return target for automatic operator optimisation
     **/
    public double getTargetAcceptanceProbability() {
        return 0.234;
    }

	/** @return value changed through automatic operator optimisation
     **/
    public double getCoercableParameterValue() {
        return Double.NaN;
    }

	/** set value that changed through automatic operator optimisation
     **/
    public void setCoercableParameterValue(double fValue) {
    }

    /** return directions on how to set operator parameters, if any **/
    public String getPerformanceSuggestion() {
        return "";
    }

    public String toString() {
		String sName = getName();
		if (sName.length() < 70) {
			sName +=  "                                                                      ".substring(sName.length(), 70);
		}
		DecimalFormat format = new DecimalFormat("#.###");
		if (!Double.isNaN(getCoercableParameterValue())) {
			String sStr = getCoercableParameterValue() + "";
			sName += sStr.substring(0, Math.min(sStr.length(), 5));
		} else {
			sName += "     ";
		}
		sName += " ";
		return sName + "\t" + m_nNrAccepted + "\t" + m_nNrRejected + "\t" +
		(m_nNrAccepted + m_nNrRejected) + "\t" +
		format.format(((m_nNrAccepted+0.0)/(m_nNrAccepted + m_nNrRejected))) +
		" " + getPerformanceSuggestion();
	}

} // class Operator