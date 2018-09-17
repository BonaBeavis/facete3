package org.aksw.facete.v3.api;

import org.aksw.jena_sparql_api.concepts.BinaryRelation;
import org.aksw.jena_sparql_api.concepts.ExprFragment2;
import org.apache.jena.graph.Node;
import org.apache.jena.rdf.model.Property;

public interface FacetDirNode {
	/** The parent of this node, may be null */
	FacetNode parent();

	/** Get the query object this node belongs to */
	FacetedQuery getQuery();
	
	/** The set of all outgoing edges - similar corrensponds to the triple ?s ?p ?o */
	//FacetEdgeSet out();

//	void as(Var var);
//	Var getAlias();
	
	FacetMultiNode via(String propertyIRI);
	FacetMultiNode via(Node node);
	FacetMultiNode via(Property property);
	
	
	/** The relation of facet and facet value */
	// TODO We may want to make this a default method that derives the relation from\
	// a ternary focus, facet, value relation
	BinaryRelation facetValueRelation();
		
	/** Facets without counts, i.e. just the available predicates */
	DataQuery<?> facets();
	
	// Get the facets of this set of values with count of their distinct values
	//Collection<FacetCount> getFacetsAndCounts();
	/** Facets and counts **/
	DataQuery<FacetCount> facetCounts();
	
	// Get the facets of this set of values with the counts referring the the query's focus
	DataQuery<FacetValueCount> facetValueCounts();
	
	/** Yield all facet value counts NOT affected by filters -
	 *  So each item can be used as a fresh filter */
	DataQuery<FacetValueCount> nonConstrainedFacetValueCounts();
	
	//ExprFragment2 constraintExpr();
	//FacetMultiNode out(Path propertyPath);
	
	
	
//	default FacetNode root() {
//		FacetNode parent = parent();
//		FacetNode result = parent == null ? this : parent.root();
//		return result;
//	}

//	default void as(String name) {
//		Var var = Var.alloc(name);
//	    as(var);
//	}
	
}


///**
// * An abstraction of a set of edges
// * 
// * @author Claus Stadler, Jul 23, 2018
// *
// */
//interface FacetEdgeSet {
//	FacetEdgeSet filter(Concept c);
//	FacetEdgeSet order();
//	
//	// session.root().get("somePredicate").outgoing()
//	//                   .out(somePredicate)
//	// orderByCount
//	// orderByIRI();
//	// orderByLabel() - default labels
//	// orderByAttribute(BinaryRelation)
//	
//	// Return the facets (properties) with their distinct value counts
//	void getFacetCounts();
//}
//
//interface FacetEdge {
//
//}