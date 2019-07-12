package org.aksw.facete.v3.api.path;

import java.util.Collection;

import org.aksw.commons.collections.trees.TreeUtils;
import org.aksw.jena_sparql_api.concepts.BinaryRelation;
import org.aksw.jena_sparql_api.concepts.TernaryRelation;
import org.apache.jena.sparql.path.P_Path0;

public interface Resolver {
	//List<P_Path0> getPath();
	Resolver getParent();
//	P_Path0 getReachingStep();

	Resolver resolve(P_Path0 step, String alias);
	
	default Resolver resolve(P_Path0 step) {
		Resolver result = resolve(step, null);
		return result;
	}
	
	
	default Resolver getRoot() {
		Resolver result = TreeUtils.getRoot(this, Resolver::getParent);
//		Resolver result = Traverser.<Resolver>forTree(x -> Collections.singleton(x.getParent()))
//				.depthFirstPostOrder(this).iterator().next();
		return result;
	}
	
//	BinaryRelation getBinaryRelation(boolean fwd);

	Collection<RelationletBinary> getPathContrib();
	
	Collection<BinaryRelation> getPaths();
	
	Collection<TernaryRelation> getContrib(boolean fwd);
}