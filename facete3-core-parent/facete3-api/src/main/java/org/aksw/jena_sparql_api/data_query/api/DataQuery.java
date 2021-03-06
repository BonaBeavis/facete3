package org.aksw.jena_sparql_api.data_query.api;

import java.util.Arrays;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.aksw.facete.v3.api.FacetValue;
import org.aksw.jena_sparql_api.concepts.Concept;
import org.aksw.jena_sparql_api.concepts.Relation;
import org.aksw.jena_sparql_api.concepts.UnaryRelation;
import org.aksw.jena_sparql_api.utils.CountInfo;
import org.aksw.jena_sparql_api.utils.ExprListUtils;
import org.aksw.jena_sparql_api.utils.Vars;
import org.apache.jena.ext.com.google.common.collect.Iterables;
import org.apache.jena.graph.Node;
import org.apache.jena.query.Query;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdfconnection.SparqlQueryConnection;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.expr.E_NotOneOf;
import org.apache.jena.sparql.expr.E_OneOf;
import org.apache.jena.sparql.expr.Expr;
import org.apache.jena.sparql.expr.ExprVar;
import org.apache.jena.sparql.expr.ExprVars;
import org.apache.jena.sparql.syntax.Element;
import org.apache.jena.sparql.syntax.ElementFilter;
import org.apache.jena.sparql.util.ExprUtils;
import org.apache.jena.sparql.util.NodeUtils;

import io.reactivex.Flowable;
import io.reactivex.Single;


// This is similar to relationjoiner
//interface DataQueryJoiner<T extends RDFNode> {
//	DataQuery<T> with(Relation relation);
//}


// Actually, this is pretty much a resource

/**
 * Query builder for retrieval batch retrieval of related *optional* information for each entity of an underlying
 * relation.
 * 
 * Hence, limit and offset apply to the base relation.
 * 
 * 
 * @author raven
 *
 */
public interface DataQuery<T extends RDFNode> {
	// For every predicate, list how many root root resources there are having this predicate
	//getPredicatesAndRootCount();
	
	DataQuery<T> pseudoRandom(Random pseudoRandom);

	
	DataQuery<T> peek(Consumer<? super DataQuery<T>> consumer);
	
	/**
	 * Access an attribute of the result class based on the
	 * mapping between attributes and properties, such as using the @Iri annotation
	 * Only works for appropriately annotated or mapped result classes
	 * See {@link FacetValue} for an example.
	 * 
	 * @param attrName
	 * @return
	 */
	NodePath get(String attrName);
	
	
	Single<Model> execConstruct();
	
	UnaryRelation fetchPredicates();
	
	DataNode getRoot();
	
	//DataMultiNode add(Property property);
	// Add an im
	DataQuery<T> add(Property property);
	DataQuery<T> addOptional(Property property);
	
	
	// this is similar to source.joinOn(attrNames).with(relation)
	DataQuery<T> filterUsing(Relation relation, String ... attrNames);
	
	
	
	// Return the same data query with intersection on the given concept
	DataQuery<T> filter(UnaryRelation concept);

	default DataQuery<T> filter(String exprStr) {
		Expr expr = ExprUtils.parse(exprStr);
		return filter(expr);
	}

	public static UnaryRelation toUnaryFiler(Expr expr) {
		Set<Var> vars = ExprVars.getVarsMentioned(expr);
		if(vars.size() != 1) {
			throw new IllegalArgumentException("Provided expression must contain exactly 1 variable");
		}
		
		Var var = Iterables.getFirst(vars, null);
		UnaryRelation result = new Concept(new ElementFilter(expr), var);		
		return result;
	}
	
//	default DataQuery<T> filter(Expr expr) {
//		UnaryRelation ur = toUnaryFiler(expr);
//		
//		return filter(ur);
//	}

	DataQuery<T> filter(Expr expr);

	// Filter injection without renaming variables
	DataQuery<T> filterDirect(Element element);

	DataQuery<T> connection(SparqlQueryConnection connection);
	SparqlQueryConnection connection();
	
	
	default DataQuery<T> only(Iterable<Node> nodes) {
		Expr e = new E_OneOf(new ExprVar(Vars.s), ExprListUtils.nodesToExprs(nodes));
		return filter(new Concept(new ElementFilter(e), Vars.s));				
	}

	default DataQuery<T> only(Node ... nodes) {
		return only(Arrays.asList(nodes));
	}

	default DataQuery<T> only(RDFNode ... rdfNodes) {
		return only(Arrays.asList(rdfNodes).stream().map(RDFNode::asNode).collect(Collectors.toList()));
	}

	default DataQuery<T> only(String ... iris) {
		return only(NodeUtils.convertToNodes(Arrays.asList(iris)));
	}

	
	
	default DataQuery<T> exclude(Iterable<Node> nodes) {
		Expr e = new E_NotOneOf(new ExprVar(Vars.s), ExprListUtils.nodesToExprs(nodes));
		return filter(new Concept(new ElementFilter(e), Vars.s));				
	}

	default DataQuery<T> exclude(Node ... nodes) {
		return exclude(Arrays.asList(nodes));
	}

	default DataQuery<T> exclude(RDFNode ... rdfNodes) {
		return exclude(Arrays.asList(rdfNodes).stream().map(RDFNode::asNode).collect(Collectors.toList()));
	}

	default DataQuery<T> exclude(String ... iris) {
		return exclude(NodeUtils.convertToNodes(Arrays.asList(iris)));
	}
	
	DataQuery<T> limit(Long limit);

	default DataQuery<T> limit(Integer limit) {
		return limit(limit == null ? null : limit.longValue());
	}

	DataQuery<T> offset(Long offset);

	default DataQuery<T> offset(Integer offset) {
		return offset(offset == null ? null : offset.longValue());
	}

	/**
	 * grouped mode (false): default semantic of construct queries
	 * partition mode (true): each row is individually mapped to a resource, used for facet value counts
	 *
	 * Partition mode is there for legacy design choices and may be deprecated,
	 * going with default semantics are strongly encouraged
	 */
	DataQuery<T> partitionMode(boolean onOrOff);
	boolean isPartitionMode();
	

	DataQuery<T> sample(boolean onOrOff);
	
	default DataQuery<T> sample() {
		return sample(true);
	}
	
	boolean isSampled();
	
	
	DataQuery<T> ordered(boolean onOrOff);
	
	default DataQuery<T> ordered() {
		return ordered(true);
	}
	
	boolean isOrdered();


	DataQuery<T> randomOrder(boolean onOrOff);
	
	default DataQuery<T> randomOrder() {
		return randomOrder(true);
	}
	
	boolean isRandomOrder();
	
	
	/**
	 * Return a SPARQL construct query together with the designated root variable
	 * 
	 * TODO Do we need to revise the return value to allow multiple root variables? Maybe yield a Relation instance?
	 * @return
	 */
	Entry<Node, Query> toConstructQuery();

	
	Relation baseRelation();

	Flowable<T> exec();


	/**
	 * Count the number of resources matching this data query's configuration
	 * using default request parameters.
	 * The result may yield a partial count (the count upon reaching a timeout)
	 * 
	 * 
	 * @return
	 */
	Single<CountInfo> count();
	Single<CountInfo> count(Long distinctItemCount, Long rowCount);
	
	
	ResolverNode resolver();
	
	
	DataQuery<T> addOrderBy(Node node, int direction);
}
