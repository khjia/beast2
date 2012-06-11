package beast.evolution.speciation;


import beast.core.Citation;
import beast.core.Description;
import beast.core.Distribution;
import beast.core.Input;
import beast.core.Input.Validate;
import beast.core.Plugin;
import beast.core.parameter.RealParameter;
import beast.core.util.CompoundDistribution;
import beast.evolution.alignment.TaxonSet;
import beast.evolution.tree.Node;
import beast.evolution.tree.Tree;
import beast.math.distributions.MRCAPrior;
import beast.math.statistic.RPNcalculator;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Description("Yule with calibrated monophyletic clades. With this prior, the marginal distribution of the" +
" calibrated nodes (the MRCA of clades) is identical to the specified calibration, but the Yule is not preserved over" +
" the whole tree space, only among sub-spaces.")
@Citation(value = "Heled J, Drummond AJ. Calibrated Tree Priors for Relaxed Phylogenetics and Divergence Time Estimation. " +
        "Syst Biol (2012) 61 (1): 138-149.", DOI = "10.1093/sysbio/syr087")
public class CalibratedYuleModel extends SpeciesTreeDistribution {

    static enum Type {
        NONE("none"),
        OVER_ALL_TOPOS("all"),
        OVER_RANKED_COUNTS("counts");

        Type(final String name) {
            this.ename = name;
        }

        public String toString() {
            return ename;
        }

        private final String ename;
    }

    // Q2R does this makes sense, or it has to be a realParameter??
    public Input<RealParameter> birthRate =
            new Input<RealParameter>("birthRate", "birth rate of splitting a linage into two", Validate.REQUIRED);

    public Input<List<CalibrationPoint>> calibrations =
            new Input<List<CalibrationPoint>>("calibrations", "Set of calibrated nodes", new ArrayList<CalibrationPoint>());//,Input.Validate.REQUIRED);

     public Input<Type> correctionType =  new Input<Type>("type", "Type of correction (default all). However, 'all'" +
             " is possible only in a few special cases (a single clade or two nested clades).",
             Type.OVER_ALL_TOPOS, Type.values());

    public Input<RPNcalculator> userMar = new Input<RPNcalculator>("logMarginal",
            "Used provided correction (log of marginal) for special cases.", (RPNcalculator)null);

    // Which correction to apply
    private Type type;

    // Calibration points, (partially) sorted by set inclusion operator on clades. (remember that partially overlapping clades are not allowed)
    CalibrationPoint[] orderedCalibrations;

    // taxa of calibrated points, in same order as 'orderedCalibrations' above. The clade is represented as an array of integers, where each
    // integer is the "node index" of the taxon in the tree, that is tree.getNode(xclades[i][k]) is the node for the k'th taxon of the i'th point.
    private int[][] xclades;

    // taxaPartialOrder[i] contains all clades immediately preceding the i'th clade under clade partial ordering.
    // (i'th clade is orderedCalibrations[i]/xclades[i]). clades are given as their index into orderedCalibrations (and so into xclades as well).
    private int[][] taxaPartialOrder;

    RPNcalculator userPDF = null; //Q2R  but would that work propagation-wise

    public CalibratedYuleModel() {}

    @Override
    public void initAndValidate() throws Exception {
        super.initAndValidate();

        type = correctionType.get();

        final Tree tree = m_tree.get();

        // shallow copy. we will be changing cals later
        final List<CalibrationPoint> cals = new ArrayList<CalibrationPoint>(calibrations.get());
        int nCals = cals.size();
        final List<TaxonSet> taxaSets = new ArrayList<TaxonSet>(nCals);
        if (cals.size() > 0) {
            xclades = new int[nCals][];

            // convenience
            for (final CalibrationPoint cal : cals) {
                taxaSets.add(cal.taxa());
            }

        } else {
        	// find calibration points from prior
        	for (Plugin plugin : outputs) {
        		if (plugin instanceof CompoundDistribution) {
        			CompoundDistribution prior = (CompoundDistribution) plugin;
        			for (Distribution distr : prior.pDistributions.get()) {
        				if (distr instanceof MRCAPrior) {
        					MRCAPrior _MRCAPrior = (MRCAPrior) distr;
        					// make sure MRCAPrior is monophyletic
        					if (!_MRCAPrior.m_bIsMonophyleticInput.get()) {
        						throw new Exception("MRCAPriors must be monophyletic for Calibrated Yule prior");
        					}
        					// create CalibrationPoint from MRCAPrior
        					CalibrationPoint cal = new CalibrationPoint();
        					cal.m_distInput.setValue(_MRCAPrior.m_distInput.get(), cal);
        					cal.m_taxonset.setValue(_MRCAPrior.m_taxonset.get(), cal);
        					cal.initAndValidate();
        					cals.add(cal);
        					taxaSets.add(cal.taxa());
        					nCals++;
        				}
        			}
        		}
        	}
        }
        if (nCals == 0) { 
        	// assume we are in beauti, back off for now
        	return;
        }
        

        for (int k = 0; k < nCals; ++k) {
            final TaxonSet tk = taxaSets.get(k);
            for (int i = k + 1; i < nCals; ++i) {
                final TaxonSet ti = taxaSets.get(i);
                if (ti.containsAny(tk)) {
                    if (!(ti.containsAll(tk) || tk.containsAll(ti))) {
                        throw new Exception("Overlapping taxaSets??");
                    }
                }
            }
        }
        orderedCalibrations = new CalibrationPoint[nCals];

        {
            int loc = taxaSets.size() - 1;
            while (loc >= 0) {
                assert loc == taxaSets.size() - 1;
                //  place maximal taxaSets at end one at a time
                int k = 0;
                for (/**/; k < taxaSets.size(); ++k) {
                    if (isMaximal(taxaSets, k)) {
                        break;
                    }
                }

                final List<String> tk = taxaSets.get(k).asStringList();
                final int tkcount = tk.size();
                this.xclades[loc] = new int[tkcount];
                for (int nt = 0; nt < tkcount; ++nt) {
                    final int taxonIndex = getTaxonIndex(tree, tk.get(nt));
                    this.xclades[loc][nt] = taxonIndex;
                    if (taxonIndex < 0) {
                        throw new Exception("Taxon not found in tree: " + tk.get(nt));
                    }
                }

                orderedCalibrations[loc] = cals.remove(k);
                taxaSets.remove(k);
                // cals and taxaSets should match
                --loc;
            }
        }

        // tio[i] will contain all taxaSets contained in the i'th clade, in the form of thier index into orderedCalibrations
        final List<Integer>[] tio = new List[orderedCalibrations.length];
        for (int k = 0; k < orderedCalibrations.length; ++k) {
            tio[k] = new ArrayList<Integer>();
        }

        for (int k = 0; k < orderedCalibrations.length; ++k) {
            final TaxonSet txk = orderedCalibrations[k].taxa();
            for (int i = k + 1; i < orderedCalibrations.length; ++i) {
                if (orderedCalibrations[i].taxa().containsAll(txk)) {
                    tio[i].add(k);
                    break;
                }
            }
        }

        this.taxaPartialOrder = new int[orderedCalibrations.length][];
        for (int k = 0; k < orderedCalibrations.length; ++k) {
            final List<Integer> tiok = tio[k];

            this.taxaPartialOrder[k] = new int[tiok.size()];
            for (int j = 0; j < tiok.size(); ++j) {
                this.taxaPartialOrder[k][j] = tiok.get(j);
            }
        }

        // true if clade is not contained in any other clade
        final boolean[] maximal = new boolean[nCals];
        for (int k = 0; k < nCals; ++k) {
            maximal[k] = true;
        }

        for (int k = 0; k < nCals; ++k) {
            for (final int i : this.taxaPartialOrder[k]) {
                maximal[i] = false;
            }
        }

        userPDF = userMar.get();
        if (userPDF == null) {

            if (type == Type.OVER_ALL_TOPOS) {
                if (nCals == 1) {
                    // closed form formula
                } else {
                    boolean anyParent = false;
                    for (final CalibrationPoint c : orderedCalibrations) {
                        if ( c.m_forParent.get() ) {
                            anyParent = true;
                        }
                    }
                    if (anyParent) {
                        throw new Exception("Sorry, not implemented: calibration on parent for more than one clade.");
                    }
                    if (nCals == 2 && orderedCalibrations[1].taxa().containsAll(orderedCalibrations[0].taxa())) {
                        // closed form formulas
                    } else {
                        setUpTables(tree.getLeafNodeCount() + 1);
                        linsIter = new CalibrationLineagesIterator(this.xclades, this.taxaPartialOrder, maximal,
                                                                   tree.getLeafNodeCount());
                        lastHeights = new double[nCals];
                    }
                }
            } else if (type == Type.OVER_RANKED_COUNTS) {
                setUpTables(tree.getLeafNodeCount() + 1);
            }
        }
    }

    Tree compatibleInitialTree() throws Exception {
        final int nCals = orderedCalibrations.length;
        final double[] lowBound = new double[nCals];
        final double[] cladeHeight = new double[nCals];

        // get lower  bound: max(lower bound of dist , bounds of nested clades)
        for (int k = 0; k < nCals; ++k) {
            final CalibrationPoint cal = orderedCalibrations[k];
            lowBound[k] = cal.dist().inverseCumulativeProbability(0);
            for( final int i : taxaPartialOrder[k] ) {
                lowBound[k] = Math.max(lowBound[k], lowBound[i]);
            }
            cladeHeight[k] = cal.dist().inverseCumulativeProbability(1);
        }

        for (int k = nCals-1; k >= 0; --k) {
            //  cladeHeight[k] should be the upper bound of k
            double upper =  cladeHeight[k];
            if( Double.isInfinite(upper) ) {
               upper = lowBound[k] + 1;
            }
            cladeHeight[k] = (upper + lowBound[k]) / 2.0;

            for( final int i : taxaPartialOrder[k] ) {
                cladeHeight[i] = Math.min(cladeHeight[i], cladeHeight[k]);
            }
        }

        final Tree tree = m_tree.get();
        final int nNodes = tree.getLeafNodeCount();
        final boolean[] used = new boolean[nNodes];

        int curLeaf = -1;
        int curInternal = nNodes-1;

        final Node[] subTree = new Node[nCals];
        for (int k = 0; k < nCals; ++k) {
            final List<Integer> freeTaxa = new ArrayList<Integer>();
            for( final int ti : xclades[k] ) {
                freeTaxa.add(ti);
            }
            for( final int i : taxaPartialOrder[k] ) {
                for( final int u : xclades[i] ) {
                    freeTaxa.remove(new Integer(u));
                }
            }

            final List<Node> sbs = new ArrayList<Node>();
            for( final int i : freeTaxa ) {
                final Node n = new Node(tree.getNode(i).getID());
                n.setNr(++curLeaf);
                n.setHeight(0.0);
                sbs.add(n);

                used[i] = true;
            }
            for( final int i : taxaPartialOrder[k] ) {
                sbs.add(subTree[i]);
            }
            final double base = sbs.get(sbs.size()-1).getHeight();
            final double step = (cladeHeight[k] - base)/ (sbs.size()-1);

            Node tr = sbs.get(0);
            for(int i = 1; i < sbs.size(); ++i) {
                tr = Node.connect(tr, sbs.get(i), base + i*step);
                tr.setNr(++curInternal);
            }
            subTree[k] = tr;
        }

        Node finalTree = subTree[nCals-1];
        double h = cladeHeight[nCals-1];

        for(int k = 0; k < used.length; ++k) {
            if( ! used[k] ) {
                final String tx = tree.getNode(k).getID();
                final Node n = new Node(tx);
                n.setHeight(0.0);
                n.setNr(++curLeaf);
                finalTree = Node.connect(finalTree, n, h+1);
                finalTree.setNr(++curInternal);
                h += 1;
            }
        }
        final Tree t = new Tree();
        t.setRoot(finalTree);
        t.initAndValidate();
        return t;
    }

    @Override
    double calculateTreeLogLikelihood(final Tree tree) {
        final double lam = birthRate.get().getArrayValue();

        double logL = calculateYuleLikelihood(tree, lam);

        final double mar = getCorrection(tree, lam);
        logL += mar;
        return logL;
    }

    private static double calculateYuleLikelihood(final Tree tree, final double lam) {
        final int taxonCount = tree.getLeafNodeCount();

        // add all lambda multipliers here
        // No normalization at the moment.  for n! use logGamma(taxonCount + 1);
        double logL = (taxonCount - 1) * Math.log(lam);

        final Node[] nodes = tree.getNodesAsArray();
        for (int i = taxonCount; i < nodes.length; i++) {
            final Node node = nodes[i];                                      assert (!node.isLeaf());
            final double height = node.getHeight();
            final double mrh = -lam * height;
            logL += mrh + (node.isRoot() ? mrh : 0);
        }
        return logL;
    }

    public double getCorrection(final Tree tree, final double lam) {
        double logL = 0.0;

        final int nCals = orderedCalibrations.length;
        final double[] hs = new double[nCals];

        for (int k = 0; k < nCals; ++k) {
            final CalibrationPoint cal = orderedCalibrations[k];
            Node c;
            final int[] taxk = xclades[k];
            if (taxk.length > 1) {
                //  find MRCA of taxa
                c = getCommonAncestor(tree, taxk);

                // only monophyletics clades can be calibrated
                if ( getLeafCount(c) != taxk.length ) {
                    return Double.NEGATIVE_INFINITY;
                }
            } else {
                c = tree.getNode(taxk[0]);
                assert cal.forParent();
            }

            if( cal.forParent() ) {
                c = c.getParent();
            }

            final double h = c.getHeight();
            // add calibration density for point
            logL += cal.logPdf(h);

            hs[k] = h;
        }

        if (Double.isInfinite(logL)) {
            // some calibration points out of range
            return logL;
        }

        if (type == Type.NONE) {
            return logL;
        }

        if (userPDF == null) {
            switch (type) {
                case OVER_ALL_TOPOS: {
                    if (nCals == 1) {
                        logL -= logMarginalDensity(lam, tree.getLeafNodeCount(), hs[0], xclades[0].length,
                                                orderedCalibrations[0].forParent());
                    } else if (nCals == 2 && taxaPartialOrder[1].length == 1) {
                        //assert !forParent[0] && !forParent[1];
                        logL -= logMarginalDensity(lam, tree.getLeafNodeCount(), hs[0], xclades[0].length,
                                                 hs[1], xclades[1].length);
                    } else {

                        if (lastLam == lam) {
                            int k = 0;
                            for (; k < hs.length; ++k) {
                                if (hs[k] != lastHeights[k]) {
                                    break;
                                }
                            }
                            if (k == hs.length) {
                                return lastValue;
                            }
                        }

                        // the slow and painful way
                        final double[] hss = new double[hs.length];
                        final int[] ranks = new int[hs.length];
                        for (int k = 0; k < hs.length; ++k) {
                            int r = 0;
                            for (final double h : hs) {
                                r += (h < hs[k]) ? 1 : 0;
                            }
                            ranks[k] = r + 1;
                            hss[r] = hs[k];
                        }
                        logL -= logMarginalDensity(lam, hss, ranks, linsIter);

                        lastLam = lam;
                        System.arraycopy(hs, 0, lastHeights, 0, lastHeights.length);
                        lastValue = logL;
                    }
                    break;
                }

                case OVER_RANKED_COUNTS: {
                    Arrays.sort(hs);
                    final int[] cs = new int[nCals + 1];
                    for( final Node n : tree.getInternalNodes() ) {
                       final double nhk = n.getHeight();
                        int i = 0;
                        for (/**/; i < hs.length; ++i) {
                            if (hs[i] >= nhk) {
                                break;
                            }
                        }
                        if (i == hs.length) {
                            cs[i]++;
                        } else {
                            if (nhk < hs[i]) {
                                cs[i]++;
                            }
                        }
                    }

                    double ll = 0;

                    ll += cs[0] * Math.log1p(-Math.exp(-lam * hs[0])) - lam * hs[0] - lfactorials[cs[0]];
                    for (int i = 1; i < cs.length - 1; ++i) {
                        final int c = cs[i];
                        ll += c * (Math.log1p(-Math.exp(-lam * (hs[i] - hs[i - 1]))) - lam * hs[i - 1]);
                        ll += -lam * hs[i] - lfactorials[c];
                    }
                    ll += -lam * (cs[nCals] + 1) * hs[nCals - 1] - lfactorials[cs[nCals] + 1];
                    ll += Math.log(lam) * nCals;

                    logL -= ll;
                    break;
                }
            }
        } else {
           final double value = userPDF.getArrayValue();
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                logL = Double.NEGATIVE_INFINITY;
          } else {
              logL -= value;
          }
        }
        return logL;
    }

    private static double logMarginalDensity(final double lam, final int nTaxa, final double h, final int nClade,
                                             final boolean forParent) {
        double lgp;

        final double lh = lam * h;

        if (forParent) {
            // n(n+1) factor left out

            lgp = -2 * lh + Math.log(lam);
            if (nClade > 1) {
                lgp += (nClade - 1) * Math.log(1 - Math.exp(-lh));
            }
        } else {
            assert nClade > 1;

            lgp = -3 * lh + (nClade - 2) * Math.log(1 - Math.exp(-lh)) + Math.log(lam);

            // root is a special case
            if (nTaxa == nClade) {
                // n(n-1) factor left out
                lgp += lh;
            } else {
                // (n^3-n)/2 factor left out
            }
        }

        return lgp;
    }

    private static double logMarginalDensity(final double lam, final int nTaxa, final double h2, final int n,
                                             final double h1, final int nm) {

        assert h2 <= h1 && n < nm;

        final int m = nm - n;

        final double elh2 = Math.exp(-lam * h2);
        final double elh1 = Math.exp(-lam * h1);

        double lgl = 2 * Math.log(lam);

        lgl += (n - 2) * Math.log(1 - elh2);
        lgl += (m - 3) * Math.log(1 - elh1);

        lgl += Math.log(1 - 2 * m * elh1 + 2 * (m - 1) * elh2
                - m * (m - 1) * elh1 * elh2 + (m * (m + 1) / 2.) * elh1 * elh1
                + ((m - 1) * (m - 2) / 2.) * elh2 * elh2);

        if (nm < nTaxa) {
            /* lgl += Math.log(0.5*(n*(n*n-1))*(n+1+m)) */
            lgl -= lam * (h2 + 3 * h1);
        } else {
            /* lgl += Math.log(lam) /* + Math.log(n*(n*n-1)) */
            lgl -= lam * (h2 + 2 * h1);
        }

        return lgl;
    }

    private double logMarginalDensity(final double lam, final double[] hs, final int[] ranks,
                                      final CalibrationLineagesIterator cli) {

        final int ni = cli.setup(ranks);

        final int nHeights = hs.length;

        final double[] lehs = new double[nHeights + 1];
        lehs[0] = 0.0;
        for (int i = 1; i < lehs.length; ++i) {
            lehs[i] = -lam * hs[i - 1];
        }

        // assert maxRank == len(sit)
        final boolean noRoot = ni == lehs.length;

        final int nLevels = nHeights + (noRoot ? 1 : 0);

        final double[] lebase = new double[nLevels];

        for (int i = 0; i < nHeights; ++i) {
            lebase[i] = lehs[i] + Math.log1p(-Math.exp(lehs[i + 1] - lehs[i]));
        }

        if (noRoot) {
            lebase[nHeights] = lehs[nHeights];
        }

        final int[] linsAtLevel = new int[nLevels];

        final int[][] joiners = cli.allJoiners();

        double val = 0;
        boolean first = true;

        int[][] linsInLevels;
        //int ccc = 0;
        while ((linsInLevels = cli.next()) != null) {
            //ccc++;
            double v = countRankedTrees(nLevels, linsInLevels, joiners, linsAtLevel);
            // 1 for root formula, 1 for kludge in iterator which sets root as 2 lineages
            if (noRoot) {
                final int ll = linsAtLevel[nLevels - 1] + 2;
                linsAtLevel[nLevels - 1] = ll;

                v -= lc2[ll] + lg2;
            }

            for (int i = 0; i < nLevels; ++i) {
                v += linsAtLevel[i] * lebase[i];
            }

            if (first) {
                val = v;
                first = false;
            } else {
                if (val > v) {
                    val += Math.log1p(Math.exp(v - val));
                } else {
                    val = v + Math.log1p(Math.exp(val - v));
                }
            }
        }

        double logc0 = 0.0;
        int totLin = 0;
        for (int i = 0; i < ni; ++i) {
            final int l = cli.nStart(i);
            if (l > 0) {
                logc0 += lNR[l];
                totLin += l;
            }
        }

        final double logc1 = lfactorials[totLin];

        double logc2 = nHeights * Math.log(lam);

        for (int i = 1; i < nHeights + 1; ++i) {
            logc2 += lehs[i];
        }

        if (!noRoot) {
            // we dont have an iterator for 0 free lineages
            logc2 += 1 * lehs[nHeights];
        }

        // Missing scale by total of all possible trees over all ranking orders.
        // Add it outside if needed for comparison.

        val += logc0 + logc1 + logc2;

        return val;
    }

    private double
    countRankedTrees(final int nLevels, final int[][] linsAtCrossings, final int[][] joiners, final int[] linsAtLevel) {
        double logCount = 0;

        for (int i = 0; i < nLevels; ++i) {
            int sumLins = 0;
            for (int k = i; k < nLevels; ++k) {
                final int[] lack = linsAtCrossings[k];
                int cki = lack[i];
                if (joiners[k][i] > 0) {
                    ++cki;
                    if (cki > 1) {
                        // can be 1 if iterator without lins - for joiners only - need to check this is correct
                        logCount += lc2[cki];
                    } //assert(cki >= 2);
                }
                final int l = cki - lack[i + 1];   //assert(l >= 0);
                logCount -= lfactorials[l];
                sumLins += l;
            }
            linsAtLevel[i] = sumLins;
        }

        return logCount;
    }

    private CalibrationLineagesIterator linsIter = null;

    double lastLam = Double.NEGATIVE_INFINITY;
    double[] lastHeights;
    double lastValue = Double.NEGATIVE_INFINITY;

    // speedup constants
    private final double lg2 = Math.log(2.0);
    private double[] lc2;
    private double[] lNR;
    private double[] lfactorials;

    private void setUpTables(final int MAX_N)
    {
        final double[] lints = new double[MAX_N];
        lc2 = new double[MAX_N];
        lfactorials = new double[MAX_N];
        lNR = new double[MAX_N];

        lints[0] = Double.NEGATIVE_INFINITY; //-infinity, should never be used
        lints[1] = 0.0;
        for (int i = 2; i < MAX_N; ++i) {
            lints[i] = Math.log(i);
        }

        lc2[0] = lc2[1] = Double.NEGATIVE_INFINITY;
        for (int i = 2; i < MAX_N; ++i) {
            lc2[i] = lints[i] + lints[i - 1] - lg2;
        }

        lfactorials[0] = 0.0;
        for (int i = 1; i < MAX_N; ++i) {
            lfactorials[i] = lfactorials[i - 1] + lints[i];
        }

        lNR[0] = Double.NEGATIVE_INFINITY; //-infinity, should never be used
        lNR[1] = 0.0;

        for (int i = 2; i < MAX_N; ++i) {
            lNR[i] = lNR[i - 1] + lc2[i];
        }
    }

    // @return true if the k'th taxa is maximal under set inclusion, i.e. it is not contained in any other set
    public static boolean isMaximal(final List<TaxonSet> taxa, final int k) {
        final TaxonSet tk = taxa.get(k);
        for (int i = 0; i < taxa.size(); ++i) {
            if (i != k) {
                if (taxa.get(i).containsAll(tk)) {
                    return false;
                }
            }
        }
        return true;
    }


    // Q2R Those generic functions could find a better home

    public static int getTaxonIndex(final Tree tree, final String taxon) {
        for (int i = 0; i < tree.getNodeCount(); i++) {
            final Node node = tree.getNode(i);
            if (node.isLeaf() && node.getID().equals(taxon) ) {
                return i;
            }
        }
        return -1;
    }

    public static Node getCommonAncestor(Node n1, Node n2) {
        // assert n1.getTree() == n2.getTree();
        while( n1 != n2 ) {
            if( n1.getHeight() < n2.getHeight() ) {
                n1 = n1.getParent();
            } else {
                n2 = n2.getParent();
            }
        }
        return n1;
    }

    // A lightweight version for finding the most recent common ancestor of a group of taxa.
    // return the node-ref of the MRCA.

    // would be nice to use nodeRef's, but they are not preserved :(
    public static Node getCommonAncestor(final Tree tree, final int[] nodes) {
        Node cur = tree.getNode(nodes[0]);

        for(int k = 1; k < nodes.length; ++k) {
            cur = getCommonAncestor(cur, tree.getNode(nodes[k]));
        }
        return cur;
    }

    /**
     * Count number of leaves in subtree whose root is node.
     *
     * @param node
     * @return the number of leaves under this node.
     */
    public static int getLeafCount(final Node node) {
        if( node.isLeaf() ) {
            return 1;
        }
        return getLeafCount(node.getLeft()) + getLeafCount(node.getRight());
    }

    // log likelihood and clades heights

    @Override
    public void init(final PrintStream out) throws Exception {
        out.print(getID() + "\t");
        for(final CalibrationPoint cp : orderedCalibrations) {
            out.print(cp.getID() + "\t");
        }
    }

    @Override
    public void log(final int nSample, final PrintStream out) {
        out.print(getCurrentLogP() + "\t");
        final Tree tree = m_tree.get();
        for(int k = 0; k < orderedCalibrations.length; ++k) {
         final CalibrationPoint cal = orderedCalibrations[k];
            Node c;
            final int[] taxk = xclades[k];
            if (taxk.length > 1) {
                //  find MRCA of taxa
                c = getCommonAncestor(tree, taxk);
            } else {
                c = tree.getNode(taxk[0]);
            }

            if( cal.forParent() ) {
                c = c.getParent();
            }

            final double h = c.getHeight();
            out.print(h+ "\t");
        }
    }
}
