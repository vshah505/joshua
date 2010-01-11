/* This file is part of the Joshua Machine Translation System.
 * 
 * Joshua is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1
 * of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free
 * Software Foundation, Inc., 59 Temple Place, Suite 330, Boston,
 * MA 02111-1307 USA
 */
package joshua.decoder.hypergraph;


import joshua.decoder.chart_parser.ComputeNodeResult;
import joshua.decoder.ff.FeatureFunction;
import joshua.decoder.ff.tm.Rule;
import joshua.corpus.vocab.SymbolTable;
import joshua.util.Regex;
import joshua.util.CoIterator;
import joshua.util.io.UncheckedIOException;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.PriorityQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * This class implements lazy k-best extraction on a hyper-graph.
 * To seed the kbest extraction, it only needs that each hyperedge 
 * should have the best_cost properly set, and it does not require 
 * any list being sorted.
 * Instead, the priority queue heap_cands will do internal sorting
 * In fact, the real crucial cost is the transition-cost at each 
 * hyperedge. 
 * We store the best-cost instead of the transition cost since it 
 * is easy to do pruning and find one-best. Moreover, the transition 
 * cost can be recovered by get_transition_cost(), though somewhat 
 * expensive.
 *
 * To recover the model cost for each individual model, we should 
 * either have access to the model, or store the model cost in the 
 * hyperedge. (For example, in the case of disk-hypergraph, we need 
 * to store all these model cost at each hyperedge.)
 *
 * @author Zhifei Li, <zhifei.work@gmail.com>
 * @version $LastChangedDate$
 */
public class KBestExtractor {
	
	/** Logger for this class. */
	private static final Logger logger =
		Logger.getLogger(KBestExtractor.class.getName());
	
	private final HashMap<HGNode,VirtualNode> virtualNodesTbl = new HashMap<HGNode,VirtualNode>();
	
	/**
	 * Shared symbol table for source language terminals, target
	 * language terminals, and shared nonterminals.
	 * <p>
	 * It may be that separate tables should be maintained for
	 * the source and target languages.
	 * <p>
	 * TODO It seems likely that only a target side symbol table
	 *      is required for this class.
	 */
	private final SymbolTable symbolTable;
	
	
	static String rootSym = "ROOT";
	static int rootID;//TODO: bug
	
	//configuratoin option
	private boolean extractUniqueNbest  = true;
	private boolean extractNbestTree = false;
	private boolean includeAlign=false;
	private boolean addCombinedScore=true;
	private boolean isMonolingual = false;
	private boolean performSanityCheck = true;
	
//	public KBestExtractor(SymbolTable symbolTable) {
//		this.p_symbolTable = symbolTable;
//	}
	
	private int sentID;
	
	public KBestExtractor(SymbolTable symbolTable, boolean extractUniqueNbest, boolean 	extractNbestTree,  boolean includeAlign,
			boolean addCombinedScore,  boolean isMonolingual,  boolean performSanityCheck){
		this.symbolTable = symbolTable;
		rootID = symbolTable.addNonterminal(rootSym);
		
		this.extractUniqueNbest = extractUniqueNbest;
		this.extractNbestTree = extractNbestTree;
		this.includeAlign = includeAlign;
		this.addCombinedScore = addCombinedScore;
		this.isMonolingual = isMonolingual;
		this.performSanityCheck = performSanityCheck;		
	}
	
	
	/*get the k-th hyp at the node
	 * format: sent_id ||| hyp ||| individual model cost ||| combined cost
	 * sent_id<0: do not add sent_id
	 * l_models==null: do not add model cost
	 * add_combined_score==f: do not add combined model cost
	 * */
	//***************** you may need to reset_state() before you call this function for the first time
	public String getKthHyp(HGNode it, int k,  int sentID, List<FeatureFunction> models) {
		this.sentID = sentID;
		VirtualNode virtualNode = addVirtualNode(it);
		DerivationState cur = virtualNode.lazyKBestExtractOnNode(symbolTable, this, k);
		if( cur==null) 
			return null;
		return getKthHyp(cur, sentID, models);
	}
	
	
	public void lazyKBestExtractOnHG(
			HyperGraph hg, List<FeatureFunction> models, 
			int topN, int sentID, final List<String> out) {
		
		CoIterator<String> coIt = new CoIterator<String>() {
			
			public void coNext(String hypStr) {
				out.add(hypStr);
			}
			
			public void finish() {					
			}
		};
		
		this.lazyKBestExtractOnHG(hg, models, topN, sentID,	coIt);
	}
	
	
	public void lazyKBestExtractOnHG(
			HyperGraph hg, List<FeatureFunction> models,
			int globalN,
			int sentID, 
			BufferedWriter out
		) throws IOException {
			
			final BufferedWriter writer;
			if (null == out) {
				writer = new BufferedWriter(new OutputStreamWriter(System.out));
			} else {
				writer = out;
			}
			
			try {
				
				CoIterator<String> coIt = new CoIterator<String>() {
					public void coNext(String hypStr) {
						try {
							writer.write(hypStr);
							writer.write("\n");
							if (logger.isLoggable(Level.FINE)) {
								logger.fine(hypStr);
							}
						} catch (IOException e) {
							throw new UncheckedIOException(e);
						}
					}
					
					public void finish() {
					}
				};
				
				this.lazyKBestExtractOnHG(hg, models, globalN, sentID, coIt);
			} catch (UncheckedIOException e) {
				e.throwCheckedException();
			}
		}
		

	private void lazyKBestExtractOnHG(
		HyperGraph hg, 
		List<FeatureFunction> featureFunctions, 
		int globalN,
		int sentID, 
		CoIterator<String> coit) {
	
		this.sentID = sentID;
		resetState();
		
		if (null == hg.goalNode) 
			return;
		
		//VirtualItem virtual_goal_item = add_virtual_item(hg.goal_item);
		try {
			int nextN = 0;
			while (true) {
				String hypStr = getKthHyp(hg.goalNode, ++nextN, sentID, featureFunctions);
				if (null == hypStr || nextN > globalN) 
					break;
				
				coit.coNext(hypStr);
			}
			//g_time_kbest_extract += System.currentTimeMillis()-start;
		} finally {
			coit.finish();
		}
	}
	
	
	public void resetState() {
		virtualNodesTbl.clear();
	}
	
	
	private String getKthHyp(DerivationState cur, int sentID, List<FeatureFunction> models){
		double[] modelCost = null;
		if(models!=null) 
			modelCost = new double[models.size()];		
		String strHypNumeric = cur.getHypothesis(symbolTable, this, extractNbestTree, modelCost,models);	
		//for(int k=0; k<model_cost.length; k++) System.out.println(model_cost[k]);
		String strHypStr = convertHyp2String(sentID, cur, models, strHypNumeric, modelCost);
		return strHypStr;
	}
	
	/* non-recursive function
	 * format: sent_id ||| hyp ||| individual model cost ||| combined cost
	 * sent_id<0: do not add sent_id
	 * l_models==null: do not add model cost
	 * add_combined_score==f: do not add combined model cost
	 * */
	private String convertHyp2String(int sentID, DerivationState cur, List<FeatureFunction> models, String strHypNumeric, double[] modelCost){
		String[] tem = Regex.spaces.split(strHypNumeric);
		StringBuffer strHyp = new StringBuffer();
		
		//####sent id
		if (sentID >= 0) { // valid sent id must be >=0
			strHyp.append(sentID);
			strHyp.append(" ||| ");
		}
		
		//TODO: consider_start_sym
		//####hyp words
		for (int t = 0; t < tem.length; t++) {
			tem[t] = tem[t].trim();
			if (extractNbestTree && ( tem[t].startsWith("(") || tem[t].endsWith(")"))) { // tree tag
				if (tem[t].startsWith("(")) {
					if (includeAlign) {
						// we must account for the {i-j} substring
						int ijStrIndex = tem[t].indexOf('{');
						String tag = this.symbolTable.getWord(Integer.parseInt(tem[t].substring(1,ijStrIndex)));
						strHyp.append('(');
						strHyp.append(tag);
						strHyp.append(tem[t].substring(ijStrIndex)); // append {i-j}
					} else {
						String tag = this.symbolTable.getWord(Integer.parseInt(tem[t].substring(1)));
						strHyp.append('(');
						strHyp.append(tag);
					}
				} else {
					//note: it may have more than two ")", e.g., "3499))"
					int firstBracketPos = tem[t].indexOf(')');//TODO: assume the tag/terminal does not have ')'
					String tag = this.symbolTable.getWord(Integer.parseInt(tem[t].substring(0,firstBracketPos)));
					strHyp.append(tag);
					strHyp.append(tem[t].substring(firstBracketPos));
				}
			} else { // terminal symbol
				strHyp.append(this.symbolTable.getWord(Integer.parseInt(tem[t])));
			}
			if (t < tem.length-1) {
				strHyp.append(' ');
			}
		}
		
		//####individual model cost, and final transition cost
		if (null != modelCost) {
			strHyp.append(" |||");
			double temSum = 0.0;
			for (int k = 0; k < modelCost.length; k++) { /* note that all the transition cost (including finaltransition cost) is already stored in the hyperedge */
				strHyp.append(String.format(" %.3f", -modelCost[k]));
				temSum += modelCost[k]*models.get(k).getWeight();
				
//				System.err.println("tem_sum: " + tem_sum + " += " + model_cost[k] + " * " + l_models.get(k).getWeight());
			}
			
			int x = 0;
			x++;
			
			//sanity check
//			if (false) {
			if (performSanityCheck) {
				if (Math.abs(cur.cost - temSum) > 1e-2) {
					StringBuilder error = new StringBuilder();
					error.append("\nIn nbest extraction, Cost does not match; cur.cost: " + cur.cost + "; temsum: " +temSum + "\n");
					//System.out.println("In nbest extraction, Cost does not match; cur.cost: " + cur.cost + "; temsum: " +tem_sum);
					for (int k = 0; k < modelCost.length; k++) {
						error.append("model weight: " + models.get(k).getWeight() + "; cost: " +modelCost[k]+ "\n");
						//System.out.println("model weight: " + l_models.get(k).getWeight() + "; cost: " +model_cost[k]);
					}
					throw new RuntimeException(error.toString());
				}
			}
		}
		
		//####combined model cost
		if (addCombinedScore) {
			strHyp.append(String.format(" ||| %.3f",-cur.cost));
		}
		
//		System.err.println("Writing hyp");
		
		return strHyp.toString();
	}
		
	
	private VirtualNode addVirtualNode(HGNode it) {
		VirtualNode res = virtualNodesTbl.get(it);
		if (null == res) {
			res = new VirtualNode(it);
			virtualNodesTbl.put(it, res);
		}
		return res;
	}

	
//=========================== class VirtualNode ===========================
	/*to seed the kbest extraction, it only needs that each hyperedge should have the best_cost properly set, and it does not require any list being sorted
	  *instead, the priority queue heap_cands will do internal sorting*/

	private  class VirtualNode {
		
		public List<DerivationState> nbests = new ArrayList<DerivationState>();//sorted ArrayList of DerivationState, in the paper is: D(^) [v]
		private PriorityQueue<DerivationState> candHeap = null; // remember frontier states, best-first;  in the paper, it is called cand[v]
		private HashMap<String, Integer>  derivationTbl = null; // rememeber which DerivationState has been explored; why duplicate, e.g., 1 2 + 1 0 == 2 1 + 0 1 
		private HashMap<String, Integer> nbestStrTbl = null; //reember unique *string* at each item, used for unique-nbest-string extraction 
		HGNode pNode = null;
		
		public VirtualNode(HGNode it) {
			this.pNode = it;
		}
		
		//return: the k-th hyp or null; k is started from one
		private DerivationState lazyKBestExtractOnNode(SymbolTable symbolTbl,
			KBestExtractor kbestExtator,
			int             k
		) {
			if (nbests.size() >= k) { // no need to continue
				return nbests.get(k-1);
			}
			
			//### we need to fill in the l_nest in order to get k-th hyp
			DerivationState res = null;
			if (null == candHeap) {
				getCandidates(symbolTbl, kbestExtator);
			}
			int tAdded = 0; //sanity check
			while (nbests.size() < k) {
				if (candHeap.size() > 0) {
					res = candHeap.poll();
					//derivation_tbl.remove(res.get_signature());//TODO: should remove? note that two state may be tied because the cost is the same
					if (extractUniqueNbest) {
						boolean useTreeFormat=false;
						String res_str = res.getHypothesis(symbolTbl, kbestExtator, useTreeFormat, null, null);
						// We pass false for extract_nbest_tree because we want; 
						// to check that the hypothesis *strings* are unique,
						// not the trees.
						//@todo zhifei: this causes trouble to monolingual grammar as there is only one *string*, need to fix it
						if (! nbestStrTbl.containsKey(res_str)) {
							nbests.add(res);
							nbestStrTbl.put(res_str,1);
						}
					} else {
						nbests.add(res);
					}
					lazyNext(symbolTbl, kbestExtator, res);//always extend the last, add all new hyp into heap_cands
					
					//debug: sanity check
					tAdded++;
					if (!extractUniqueNbest && tAdded > 1) { // this is possible only when extracting unique nbest
						throw new RuntimeException("In lazyKBestExtractOnNode, add more than one time, k is " + k);
					}
				} else {
					break;
				}
			}
			if (nbests.size() < k) {
				res = null;//in case we do not get to the depth of k
			}
			//debug: sanity check
			//if (l_nbest.size() >= k && l_nbest.get(k-1) != res) {
			//throw new RuntimeException("In lazy_k_best_extract, ranking is not correct ");
			//}
			
			return res;
		}
		
		//last: the last item that has been selected, we need to extend it
		//get the next hyp at the "last" hyperedge
		private void lazyNext(SymbolTable symbolTbl, KBestExtractor kbestExtator, DerivationState last) {
			if (null == last.edge.getAntNodes()) {
				return;
			}
			for (int i = 0; i < last.edge.getAntNodes().size(); i++) { // slide the ant item
				HGNode it = (HGNode) last.edge.getAntNodes().get(i);
				VirtualNode virtualIT = kbestExtator.addVirtualNode(it);
				int[] newRanks = new int[last.ranks.length];
				for (int c = 0; c < newRanks.length;c++) {
					newRanks[c] = last.ranks[c];
				}
				
				newRanks[i] = last.ranks[i] + 1;
				String newSig = getDerivationStateSignature(last.edge, newRanks, last.edgePos);
				
				//why duplicate, e.g., 1 2 + 1 0 == 2 1 + 0 1 
				if (derivationTbl.containsKey(newSig)) {
					continue;
				}
				virtualIT.lazyKBestExtractOnNode(symbolTbl, kbestExtator, newRanks[i]);
				if (newRanks[i] <= virtualIT.nbests.size() // exist the new_ranks[i] derivation
				  /*&& "t" is not in heap_cands*/) { // already checked before, check this condition
					double cost = last.cost - ((DerivationState)virtualIT.nbests.get(last.ranks[i]-1)).cost + ((DerivationState)virtualIT.nbests.get(newRanks[i]-1)).cost;
					DerivationState t = new DerivationState(last.parentNode, last.edge, newRanks, cost, last.edgePos);
					candHeap.add(t);
					derivationTbl.put(newSig,1);
				}
			}
		}

		//this is the seeding function, for example, it will get down to the leaf, and sort the terminals
		//get a 1best from each hyperedge, and add them into the heap_cands
		private void getCandidates(SymbolTable symbolTbl, KBestExtractor kbestExtator) {
			candHeap = new PriorityQueue<DerivationState>();
			derivationTbl = new HashMap<String,Integer>();
			if (extractUniqueNbest) {
				nbestStrTbl = new HashMap<String,Integer>();
			}
			//sanity check
			if (null == pNode.hyperedges) {
				throw new RuntimeException("l_hyperedges is null in get_candidates, must be wrong");
			}
			int pos = 0;
			for (HyperEdge edge : pNode.hyperedges) {
				DerivationState t = getBestDerivation(symbolTbl, kbestExtator, pNode, edge, pos);
//				why duplicate, e.g., 1 2 + 1 0 == 2 1 + 0 1 , but here we should not get duplicate
				if (!derivationTbl.containsKey(t.getSignature())) {
					candHeap.add(t);
					derivationTbl.put(t.getSignature(),1);
				} else { // sanity check
					throw new RuntimeException(
						"get duplicate derivation in get_candidates, this should not happen"
						+ "\nsignature is " + t.getSignature()
						+ "\nl_hyperedge size is " + pNode.hyperedges.size()
						);
				}
				pos++;
			}	
			
//			TODO: if tem.size is too large, this may cause unnecessary computation, we comment the segment to accormodate the unique nbest extraction			
			/*if(tem.size()>global_n){
				heap_cands=new PriorityQueue<DerivationState>();
				for(int i=1; i<=global_n; i++)
					heap_cands.add(tem.poll());
			}else
				heap_cands=tem;
			*/	
		}
		
		//get my best derivation, and recursively add 1best for all my children, used by get_candidates only
		private DerivationState getBestDerivation(SymbolTable symbolTbl, KBestExtractor kbestExtator, HGNode parentNode, HyperEdge hyperEdge, int edgePos){
			int[] ranks;
			double cost=0;
			if(hyperEdge.getAntNodes()==null){//axiom
				ranks=null;
				cost=hyperEdge.bestDerivationCost;//seeding: this hyperedge only have one single translation for the terminal symbol
			}else{//best combination					
				ranks = new int[hyperEdge.getAntNodes().size()];					
				for(int i=0; i < hyperEdge.getAntNodes().size();i++){//make sure the 1best at my children is ready
					ranks[i]=1;//rank start from one									
					HGNode child_it = (HGNode) hyperEdge.getAntNodes().get(i);//add the 1best for my children
					VirtualNode virtual_child_it = kbestExtator.addVirtualNode(child_it);
					virtual_child_it.lazyKBestExtractOnNode(symbolTbl, kbestExtator,  ranks[i]);
				}
				cost = hyperEdge.bestDerivationCost;//seeding
			}				
			DerivationState t = new DerivationState(parentNode, hyperEdge, ranks, cost, edgePos );
			return t;
		}
	};
	

//===============================================
//	class DerivationState
//===============================================
	/*each Node will maintain a list of this, each of which corresponds to a hyperedge and its children's ranks.
	 * remember the ranks of a hyperedge node
	 * used for kbest extraction*/
	
	//each DerivationState rougly correponds a hypothesis 
	private class DerivationState implements Comparable<DerivationState> 
	{
		HGNode parentNode;
		HyperEdge edge;//in the paper, it is "e"		
		//**lesson: once we define this as a static variable, which cause big trouble
		int edgePos; //this is my position in my parent's Item.l_hyperedges, used for signature calculation
		int[] ranks;//in the paper, it is "j", which is a ArrayList of size |e|
		double cost;//the cost of this hypthesis
		
		public DerivationState(HGNode pa, HyperEdge e, int[] r, double c ,int pos){
			parentNode = pa;
			edge =e ;
			ranks = r;
			cost=c;
			edgePos=pos;
		}
		
		private String getSignature() {
			StringBuffer res = new StringBuffer();
			//res.apend(p_edge2.toString());//Wrong: this may not be unique to identify a hyperedge (as it represent the class name and hashcode which my be equal for different objects)
			res.append(edgePos);
			if (null != ranks) {
				for (int i = 0; i < ranks.length;i++) {
					res.append(' ');
					res.append(ranks[i]);
				}
			}
			return res.toString();
		}
		
		
		
		//get the numeric sequence of the particular hypothesis
		//if want to get model cost, then have to set model_cost and l_models
		private String getHypothesis(SymbolTable symbolTbl, KBestExtractor kbestExtator, boolean useTreeFormat, double[] modelCost,
				List<FeatureFunction> models) {
			//### accumulate cost of p_edge into model_cost if necessary
			if (null != modelCost) {
				computeCost(parentNode, edge, modelCost, models);
			}
			
			//### get hyp string recursively
			StringBuffer res = new StringBuffer();
			Rule rl = edge.getRule();
			
			if (null == rl) { // hyperedges under "goal item" does not have rule
				if (useTreeFormat) {
					//res.append("(ROOT ");
					res.append('(');
					res.append(rootID);
					if (includeAlign) {
						// append "{i-j}"
						res.append('{');
						res.append(parentNode.i);
						res.append('-');
						res.append(parentNode.j);
						res.append('}');
					}
					res.append(' ');
				}
				for (int id = 0; id < edge.getAntNodes().size(); id++) {
					HGNode child = edge.getAntNodes().get(id);
					VirtualNode virtualChild = kbestExtator.addVirtualNode(child);
					res.append( virtualChild.nbests.get(ranks[id]-1).getHypothesis(symbolTbl,kbestExtator, useTreeFormat, modelCost, models) );
					if (id < edge.getAntNodes().size()-1) res.append(' ');
				}
				if (useTreeFormat) 
					res.append(')');
			} else {
				if (useTreeFormat) {
					res.append('(');
					res.append(rl.getLHS());
					if (includeAlign) {
						// append "{i-j}"
						res.append('{');
						res.append(parentNode.i);
						res.append('-');
						res.append(parentNode.j);
						res.append('}');
					}
					res.append(' ');
				}
				if (!isMonolingual) { // bilingual
					int[] english = rl.getEnglish();
					for (int c = 0; c < english.length; c++) {
						if (symbolTbl.isNonterminal(english[c])) {
							int id = symbolTbl.getTargetNonterminalIndex(english[c]);
							HGNode child = edge.getAntNodes().get(id);
							VirtualNode virtualChild = kbestExtator.addVirtualNode(child);
							res.append( virtualChild.nbests.get(ranks[id]-1).getHypothesis(symbolTbl, kbestExtator, useTreeFormat, modelCost, models));
						} else {
							res.append(english[c]);
						}
						if (c < english.length-1) res.append(' ');
					}
				} else { // monolingual
					int[] french = rl.getFrench();
					int nonTerminalID = 0;//the position of the non-terminal in the rule
					for (int c = 0; c < french.length; c++) {
						if (symbolTbl.isNonterminal(french[c])) {
							HGNode child =  edge.getAntNodes().get(nonTerminalID);
							VirtualNode virtualChild = kbestExtator.addVirtualNode(child);
							res.append( virtualChild.nbests.get(ranks[nonTerminalID]-1).getHypothesis(symbolTbl,kbestExtator, useTreeFormat, modelCost, models));
							nonTerminalID++;
						} else {
							res.append(french[c]);
						}
						if (c < french.length-1) res.append(' ');
					}
				}
				if (useTreeFormat) res.append(')');
			}
			
			return res.toString();
		}
		
		/*
		//TODO: we assume at most one lm, and the LM is the only non-stateles model
		//another potential difficulty in handling multiple LMs: symbol synchronization among the LMs
		//accumulate hyperedge cost into model_cost[], used by get_hyp()
		private void compute_cost_not_used(HyperEdge dt, double[] model_cost, ArrayList l_models){
			if(model_cost==null) return;
			
			//System.out.println("Rule is: " + dt.rule.toString());
			double stateless_transition_cost =0;
			FeatureFunction lm_model =null;
			int lm_model_index = -1;
			for(int k=0; k< l_models.size(); k++){
				FeatureFunction m = (FeatureFunction) l_models.get(k);	
				double t_res =0;
				if(m.isStateful() == false){//stateless feature
					if(dt.get_rule()!=null){//hyperedges under goal item do not have rules
						FFTransitionResult tem_tbl =  m.transition(dt.get_rule(), null, -1, -1);
						t_res = tem_tbl.getTransitionCost();
					}else{//final transtion
						t_res = m.finalTransition(null);
					}
					model_cost[k] += t_res;
					stateless_transition_cost += t_res*m.getWeight();
				}else{
					lm_model = m;
					lm_model_index = k;
				}
			}
			if(lm_model_index!=-1)//have lm model
				model_cost[lm_model_index] += (dt.get_transition_cost(false)-stateless_transition_cost)/lm_model.getWeight();
		}
		*/
		
		
		//accumulate cost into modelCost
		private void computeCost(HGNode parentNode, HyperEdge dt, double[] modelCost, List<FeatureFunction> models){
			if (null == modelCost) 
				return;
			//System.out.println("Rule is: " + dt.rule.toString());
			//double[] transitionCosts = ComputeNodeResult.computeModelTransitionCost(models, dt.getRule(), dt.getAntNodes(), parentNode.i, parentNode.j, dt.getSourcePath(), sentID);
			double[] transitionCosts = ComputeNodeResult.computeModelTransitionCost(models, dt, parentNode.i, parentNode.j, sentID);
		
			for(int i=0; i<transitionCosts.length; i++){
				modelCost[i] += transitionCosts[i];
			}
		}
		
		
		//natual order by cost
		public int compareTo(DerivationState another) {
			if (this.cost < another.cost) {
				return -1;
			} else if (this.cost == another.cost) {
				return 0;
			} else {
				return 1;
			}
		}
	}//end of Class DerivationState
	
	private String getDerivationStateSignature(HyperEdge edge2, int[] ranks2, int pos) {
		StringBuffer sb = new StringBuffer();
		//sb.apend(p_edge2.toString());//Wrong: this may not be unique to identify a hyperedge (as it represent the class name and hashcode which my be equal for different objects)
		sb.append(pos);
		if (null != ranks2) {
			for (int i = 0; i < ranks2.length; i++) {
				sb.append(' ');
				sb.append(ranks2[i]);
			}
		}
		return sb.toString();
	}
}
