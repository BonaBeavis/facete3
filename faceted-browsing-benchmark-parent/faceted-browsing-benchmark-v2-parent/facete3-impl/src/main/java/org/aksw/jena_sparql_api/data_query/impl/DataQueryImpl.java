package org.aksw.jena_sparql_api.data_query.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.aksw.commons.collections.generator.Generator;
import org.aksw.commons.collections.trees.TreeUtils;
import org.aksw.facete.v3.api.AliasedPath;
import org.aksw.facete.v3.api.path.Resolver;
import org.aksw.facete.v3.experimental.ResolverNodeImpl;
import org.aksw.facete.v3.experimental.Resolvers;
import org.aksw.jena_sparql_api.algebra.utils.AlgebraUtils;
import org.aksw.jena_sparql_api.beans.model.EntityModel;
import org.aksw.jena_sparql_api.beans.model.EntityOps;
import org.aksw.jena_sparql_api.beans.model.PropertyOps;
import org.aksw.jena_sparql_api.concepts.Concept;
import org.aksw.jena_sparql_api.concepts.Relation;
import org.aksw.jena_sparql_api.concepts.RelationImpl;
import org.aksw.jena_sparql_api.concepts.UnaryRelation;
import org.aksw.jena_sparql_api.data_query.api.DataMultiNode;
import org.aksw.jena_sparql_api.data_query.api.DataNode;
import org.aksw.jena_sparql_api.data_query.api.DataQuery;
import org.aksw.jena_sparql_api.data_query.api.NodeAliasedPath;
import org.aksw.jena_sparql_api.data_query.api.NodePath;
import org.aksw.jena_sparql_api.data_query.api.PathAccessor;
import org.aksw.jena_sparql_api.data_query.api.ResolverNode;
import org.aksw.jena_sparql_api.data_query.api.SPath;
import org.aksw.jena_sparql_api.mapper.PartitionedQuery1;
import org.aksw.jena_sparql_api.mapper.impl.type.RdfTypeFactoryImpl;
import org.aksw.jena_sparql_api.pathlet.Path;
import org.aksw.jena_sparql_api.pathlet.PathletJoinerImpl;
import org.aksw.jena_sparql_api.pathlet.PathletSimple;
import org.aksw.jena_sparql_api.relationlet.RelationletElementImpl;
import org.aksw.jena_sparql_api.relationlet.RelationletSimple;
import org.aksw.jena_sparql_api.rx.SparqlRx;
import org.aksw.jena_sparql_api.utils.CountInfo;
import org.aksw.jena_sparql_api.utils.ElementUtils;
import org.aksw.jena_sparql_api.utils.QueryUtils;
import org.aksw.jena_sparql_api.utils.VarGeneratorBlacklist;
import org.apache.jena.enhanced.EnhGraph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.query.Query;
import org.apache.jena.query.SortCondition;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdfconnection.SparqlQueryConnection;
import org.apache.jena.sparql.algebra.optimize.Rewrite;
import org.apache.jena.sparql.core.BasicPattern;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.expr.E_Random;
import org.apache.jena.sparql.expr.Expr;
import org.apache.jena.sparql.expr.ExprVar;
import org.apache.jena.sparql.expr.NodeValue;
import org.apache.jena.sparql.expr.aggregate.AggSample;
import org.apache.jena.sparql.graph.NodeTransform;
import org.apache.jena.sparql.syntax.Element;
import org.apache.jena.sparql.syntax.ElementSubQuery;
import org.apache.jena.sparql.syntax.ElementTriplesBlock;
import org.apache.jena.sparql.syntax.PatternVars;
import org.apache.jena.sparql.syntax.Template;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.jsonldjava.shaded.com.google.common.collect.Maps;
import com.google.common.collect.BiMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Range;

import io.reactivex.Flowable;
import io.reactivex.Single;

/**
 * A more general view on path steps:
 * What bothers me with the current path approach is, that it does not allow one to navigate to or along
 * a predicate object of the *same* triple. Not that this is a commonly needed operation, but it
 * shows a shortcoming of the approach.
 * 
 * "?s ?o WHERE ?s ?p ?o": How to navigate to ?p ?
 * 
 * 
 * Path steps are operations on a component-based binary relation
 * Component-based means, that we are referring to a specific component of a triple pattern in that relation
 * - in constrast to a specific variable within a BGP (which can occur in many components).
 * 
 * 
 * Given a binary relation ?s ?o WHERE { ?s ?p ?o },
 * we could have an operation that modifies the relation target
 * from e.g. the object of a triple pattern to the predicate component:
 * 
 * toTriplePredicate(?s ?o WHERE { ?s ?p ?o }) -&gt; (?s ?p WHERE { ?s ?p ?o }) 
 *  
 * Following a predicate could then be realized using a preconfigured operator created using a function:
 * stepFwd('foo:bar')(?s ?o WHERE { ?s ?p ?o }) -&gt; ?s ?z WHERE { ?s ?p ?o . ?o foo:bar ?z}
 *
 * In any case, the questions that arise are:
 * - When we allow traversals between ternary relations (of which triple patterns are a special case), then one component is always the source one,
 * where as two components remain
 * 
 * Well, we don't need and want to modify the concept that a path intensionally connects two sets of RDF terms.
 * What is missing, is a triple-based traversal, where its possible to select the predicate or 'target' component
 * (and possibly add some constraint on these components
 * 
 * But still, it leads to point, where a path segment corresponds to either
 * (a) the addition of a ternary relation or
 * (b) switching to a different component within this added relation
 * Then again, since we are using ternary relation objects, we can easily navigate to the subject, target, or source component
 * 
 * 
 */

/**
 * Optional paths
 * 
 * Given the following graph patterns, are the some algebraic relations that hold between them?
 * 
 * (a) { OPTIONAL { X } }
 * (b) { OPTIONAL { X OPTIONAL Y } }
 * (c) { OPTIONAL { X Y } }
 * 
 * c: If for some binding of X there is no suitable binding of Y, the whole binding will be dropped
 * 
 * 
 * Maybe optional should be part of the traversal api?
 * fwd('foo').opt().fwd('bar')
 * 
 * 
 * 
 */

/**
 * NodeTransformer that detects nodes that are path references,
 * and resolves them to appropriate variables, thereby keeping
 * track of graph patterns that need to be injected into the
 * base query.
 * 
 * Paths can be references as mandatory or as optional.
 * 
 * <pre>
 *   <ul>
 *     <li>OPTIONAL { X } X -&gt; X</li>
 *     <li>OPTIONAL { HEAD } BODY OPTIONAL { TAIL } -&gt; should not happen, because if the body is mandatory, so should be head</li>
 *   </ul>
 * </pre>
 * 
 * @author raven
 *
 * @param <T>
 */
class NodeTransformResolvePaths
	implements NodeTransform
{
	protected ResolverNode resolver; 
	protected Generator<Var> vargen;
	
	protected BiMap<Var, ResolverNode> varToResolver;
	
	// If a path is referenced as mandatory, all parents are marked as mandatory as well
	// Phase 1: Collection: Find all path references, mark parents as mandatory as needed
	// Phaso 2: Graph Pattern Generation: For each referenced path, create graph patterns for its parent elements as needed
	
//	protected 
	
	public NodeTransformResolvePaths(ResolverNode resolver, Generator<Var> vargen,
			BiMap<Var, ResolverNode> varToResolver) {
		super();
		this.resolver = resolver;
		this.vargen = vargen;
		this.varToResolver = varToResolver;
	}

	@Override
	public Node apply(Node n) {
		Node result = n instanceof NodeAliasedPath
				? $apply((NodeAliasedPath)n)
				: n;
		return result;
	}
	
	public Node $apply(NodeAliasedPath np) {
		AliasedPath ap = np.getPath();
		ResolverNode target = resolver.walk(ap);
		
		return null;
	}
	
}


public class DataQueryImpl<T extends RDFNode>
	implements DataQuery<T>
{
	// TODO Actually, there should be no logger here - instead there
	// should be some peekQuery(Consumer<Query>) if one wants to know the query
	private static final Logger logger = LoggerFactory.getLogger(DataQueryImpl.class);

	
	protected SparqlQueryConnection conn;
	
//	protected Node rootVar;
//	protected Element baseQueryPattern;

	
	// FIXME for generalization, probably this attribute has to be replaced by
	// a something similar to a list of roots; ege DataNode
	protected Relation baseRelation;
	
	protected Template template;
	
	protected List<DataNode> dataNodes;
	
//	protected Range<Long> range;

	protected Long limit;
	protected Long offset;
	
	protected UnaryRelation filter;
	
	protected List<Element> directFilters = new ArrayList<>();
	
	protected boolean ordered;
	protected boolean randomOrder;
	protected boolean sample;
	
	
	protected Random pseudoRandom = null;

	
	protected Class<T> resultClass;

	protected List<SortCondition> sortConditions = new ArrayList<>();
	
	public DataQueryImpl(SparqlQueryConnection conn, Node rootNode, Element baseQueryPattern, Template template, Class<T> resultClass) {
		this(conn, new Concept(baseQueryPattern, (Var)rootNode), template, resultClass);
	}

	public DataQueryImpl(SparqlQueryConnection conn, Relation baseRelation, Template template, Class<T> resultClass) {
		super();
		this.conn = conn;
//		this.rootVar = rootNode;
//		this.baseQueryPattern = baseQueryPattern;
		this.baseRelation = baseRelation;
		this.template = template;
		this.resultClass = resultClass;
	}

	@Override
	public SparqlQueryConnection connection() {
		return conn;
	}
	
	@Override
	public DataQuery<T> connection(SparqlQueryConnection connection) {
		this.conn = connection;
		return this;
	}
	
	public <U extends RDFNode> DataQuery<U> as(Class<U> clazz) {
		return new DataQueryImpl<U>(conn, baseRelation, template, clazz);
	}
	
	@Override
	public DataQuery<T> limit(Long limit) {
		this.limit = limit;
		return this;
	}

	@Override
	public DataQuery<T> offset(Long offset) {
		this.offset = offset;
		return this;
	}
	
	@Override
	public DataQuery<T> sample(boolean onOrOff) {
		this.sample = onOrOff;
		return this;
	}
	
	@Override
	public boolean isSampled() {
		return sample;
	}

	
	@Override
	public DataQuery<T> ordered(boolean onOrOff) {
		this.ordered = onOrOff;
		return this;
	}
	
	@Override
	public boolean isOrdered() {
		return ordered;
	}

	@Override
	public boolean isRandomOrder() {
		return randomOrder;
	}

	@Override
	public DataQuery<T> randomOrder(boolean onOrOff) {
		this.randomOrder = onOrOff;
		return this;
//		return this;
	}

	//protected void setOffset(10);

	
	/**
	 * Setting a random number generator (rng) makes query execution deterministic:
	 * Random effects on result sets will be processed in the client:
	 * Randomly ordered result sets will be fully loaded into the client and shuffeled in respect
	 * to the given rng.
	 * 
	 * TODO We may need extra an extra 'deterministic' attribute to indicate
	 * whether to sort result sets - the problem is, that the same query on data loaded at different
	 * times may yield different results. For practical purposes it rarely happens that the same query
	 * yields different results if there were no changes in the data. But maybe it could happen
	 * if a DB did something similar to postgres' vacuum process?
	 * 
	 * @param pseudoRandom
	 * @return
	 */
	@Override
	public DataQuery<T> pseudoRandom(Random pseudoRandom) {
		this.pseudoRandom = pseudoRandom;
		return this;
	}
	
	
	@Override
	public Concept fetchPredicates() {
		// TODO Auto-generated method stub
		return null;
	}


	@Override
	public DataNode getRoot() {
		// TODO Auto-generated method stub
		return null;
	}


	@Override
	public DataMultiNode add(Property property) {
		// TODO Auto-generated method stub
		return null;
	}


	@Override
	public DataQuery<T> filter(UnaryRelation concept) {
		if(concept != null) {
			if(filter == null) {
				filter = concept;
			} else {
				filter = filter.joinOn(filter.getVar()).with(concept).toUnaryRelation();
			}
		}

		return this;
	}

	@Override
	public DataQuery<T> filterDirect(Element element) {
		directFilters.add(element);
		
		return this;
	}

	
	
	@Override
	public DataQuery<T> peek(Consumer<? super DataQuery<T>> consumer) {
		consumer.accept(this);
		return this;
	}
	
	@Override
	public DataQuery<T> filterUsing(Relation relation, String... attrNames) {

		if(relation != null) {
			List<Var> vars = Arrays.asList(attrNames).stream()
					.map(this::resolveAttrToVar)
					.collect(Collectors.toList());
	
			baseRelation = baseRelation.joinOn(vars).with(relation);
		}
		return this;
	}

	public Var resolveAttrToVar(String attr) {
		EntityOps entityOps = EntityModel.createDefaultModel(resultClass, null);
		
		PropertyOps pops = entityOps.getProperty(attr);
		String iri = RdfTypeFactoryImpl.createDefault().getIri(entityOps, pops);

		// FIXME HACK We need to start from the root node instead of iterating the flat list of triple patterns
		Triple match = template.getBGP().getList().stream()
			.filter(t -> t.getPredicate().getURI().equals(iri))
			.findFirst()
			.orElse(null);

		Node node = Optional.ofNullable(match.getObject())
				.orElseThrow(() -> new RuntimeException("No member with name " + attr + " in " + resultClass));

		Var result = (Var)node;
		return result;
	}

	
	/**
	 * Hibernate-like get method which resolves an attribute of the resultClass
	 * to a SPARQL variable of the underlying partitioned query's template
	 * 
	 * The result is a Node that can be used in Jena SPARQL {@link Expr} expressions,
	 * which can be subsequently passed to e.g. filter(expr) of this class for filtering.
	 * 
	 */
	@Override
	public NodePath get(String attr) {
		
		Var var = resolveAttrToVar(attr);
//		Node node = Optional.ofNullable(iri)
//				.map(NodeFactory::createURI)				
//				.orElseThrow(() -> new MemberNotFoundException("No member with name " + attr + " in " + resultClass));

//		
//		
//		Node node = Optional.ofNullable(pops)
//			.map(p -> p.findAnnotation(Iri.class))
//			.map(Iri::value)
//			.map(NodeFactory::createURI)
//			.orElseThrow(() -> new MemberNotFoundException("No member with name " + attr + " in " + resultClass));
		
//		System.out.println("Found: " + node);
		
		Model m = ModelFactory.createDefaultModel();
		SPath tmp = new SPathImpl(m.createResource().asNode(), (EnhGraph)m);
		tmp.setAlias(var.getName());
		
//		tmp = tmp.get(node.getURI(), false);
		
		
		//tmp.setAlias(alias);

		NodePath result = new NodePath(tmp);
		
		return result;
	}
	
	
	
	@Override
	public DataQuery<T> filter(Expr expr) {
		PathAccessor<SPath> pathAccessor = new PathAccessorSPath();
		PathToRelationMapper<SPath> mapper = new PathToRelationMapper<>(pathAccessor, "w");

		Collection<Element> elts = FacetedQueryGenerator.createElementsForExprs(mapper, pathAccessor, Collections.singleton(expr), false);

		// FIXME Hack to obtain a zero-length path; equals on SPath is broken
		SPath root = PathAccessorUtils.getPathsMentioned(expr, pathAccessor::tryMapToPath).values().stream()
			.map(p -> TreeUtils.findRoot(p, pathAccessor::getParent))
			.findFirst()
			.orElseThrow(() -> new RuntimeException("Should not happen: Expr without path - " + expr));
		
		
		//SPath root = ModelFactory.createDefaultModel().createResource().as(SPath.class);
		//Var rootVar = (Var)pathAccessor.(root);
		
		if(false) {
		Var rootVar = (Var)mapper.getNode(root);
		
		UnaryRelation tmp = new Concept(ElementUtils.groupIfNeeded(elts), rootVar);
		filter(tmp);
		} else {
			filterDirect(ElementUtils.groupIfNeeded(elts));
		}
		//TreeUtils.getRoot(item, predecessor)
		
		//NodeTransform xform = PathToRelationMapper.createNodeTransformSubstitutePathReferences(new PathAccessorSPath());

		//Expr e = expr.applyNodeTransform(xform);
		// Transformation has to be done using NodeTransformExpr as
		// it correctly substitutes NodeValues with ExprVars
//		Expr e = ExprTransformer.transform(new NodeTransformExpr(xform), expr);
//		
//		UnaryRelation f = DataQuery.toUnaryFiler(e);
//		filter(f);
		
		return this;
	}

	
	
	// Note: The construct template may be empty - use in conjunction with ReactiveSparqlUtils.execPartitioned()
	// TODO Rename to getEffectiveQuery
//	@Override
//	public Entry<Node, Query> toConstructQuery() {
//		// This method perform in-place transform on the query object
//		Entry<Node, Query> tmp = toBaseConstructQuery();
//
//		Var rootVar = (Var)tmp.getKey();
//		Query baseQuery = tmp.getValue();
//		Resolver resolver = Resolvers.from(rootVar, baseQuery);
//		
//		PathletContainerImpl pathlet = new PathletContainerImpl(resolver);
//		// Add the base query to the pathlet, with variable ?s joining with the pathlet's root
//		// and ?s also being the connector for subsequent joins
//		pathlet.add(new PathletSimple(rootVar, rootVar, new RelationletElementImpl(baseQuery.getQueryPattern()).fixAll()));
//
//		// Substitute all NodePathletPath objects with NodePathletVarRef objects
//		NodeTransform xform1 = new NodeTransformPathletPathResolver(pathlet);
//		QueryUtils.applyNodeTransform(baseQuery, xform1);
//
//		// Now that all paths have been collected and added to the pathlet
//		// materalize it
//		RelationletNested rn = pathlet.materialize();
//		
//		// Resolve all var refs against the materialized relationlet
//		NodeTransform xform2 = new NodeTransformPathletVarRefResolver(rn);
//		QueryUtils.applyNodeTransform(baseQuery, xform2);
//		
//		Element e = rn.getElement();
//
//		baseQuery.setQueryPattern(e);
//
//		return tmp;
//	}
	
	@Override
	public Entry<Node, Query> toConstructQuery() {
		
		Set<Var> vars = new LinkedHashSet<>();
		Node rootVar = baseRelation.getVars().get(0);
		if(rootVar.isVariable()) {
			vars.add((Var)rootVar);
		}
		
		//boolean canAsConstruct = template != null && !template.getBGP().isEmpty();

		if(template != null) {
			vars.addAll(PatternVars.vars(new ElementTriplesBlock(template.getBGP())));
		}

		Query query = new Query();
		query.setQuerySelectType();
		for(Var v : vars) {
			query.getProject().add(v);
		}
//		query.setQueryConstructType();
//		query.setConstructTemplate(template);

		
		//query.setQueryResultStar(true);
		//query.setQuerySelectType();

		// Start with a select query, optimize it, and at the end turn it into construct
//		query.setQuerySelectType();

		
		Element baseQueryPattern = baseRelation.getElement();
		
		Element effectivePattern = filter == null
				? baseQueryPattern
				: new RelationImpl(baseQueryPattern, new ArrayList<>(PatternVars.vars(baseQueryPattern))).joinOn((Var)rootVar).with(filter).getElement()
				;

		if(!directFilters.isEmpty()) {
			effectivePattern = ElementUtils.groupIfNeeded(Iterables.concat(Collections.singleton(effectivePattern), directFilters));
		}

		
		boolean deterministic = pseudoRandom != null;

		if(sample) {
			Set<Var> allVars = new LinkedHashSet<>();
			allVars.addAll(vars);
			allVars.addAll(PatternVars.vars(baseQueryPattern));
			
			Generator<Var> varGen = VarGeneratorBlacklist.create(allVars);
			Var innerRootVar = varGen.next();
			
//			if(baseQueryPattern instanceof ElementSubQuery) {
//				QueryGroupExecutor.createQueryGroup()
//
//			}
			
			Element innerE = ElementUtils.createRenamedElement(effectivePattern, Collections.singletonMap(rootVar, innerRootVar));

			
			Query inner = new Query();
			inner.setQuerySelectType();
			inner.setQueryPattern(innerE);
			Expr agg = inner.allocAggregate(new AggSample(new ExprVar(innerRootVar)));
			inner.getProject().add((Var)rootVar, agg);
			
			if(!(randomOrder && deterministic)) {
				QueryUtils.applySlice(inner, offset, limit, false);
			}

			Element e = ElementUtils.groupIfNeeded(new ElementSubQuery(inner), effectivePattern);
						
			query.setQueryPattern(e);
		} else {
			
			// TODO Controlling distinct should be possible on this class
			query.setDistinct(true);
	
			query.setQueryPattern(effectivePattern);
			
			if(!(randomOrder && deterministic)) {
				QueryUtils.applySlice(query, offset, limit, false);
			}
		}
		
		
		if(ordered) {
			query.addOrderBy(new ExprVar(rootVar), Query.ORDER_ASCENDING);
		}		

		if(randomOrder && !deterministic) {
			query.addOrderBy(new E_Random(), Query.ORDER_ASCENDING);
//			query.addOrderBy(new E_RandomPseudo(), Query.ORDER_ASCENDING);
		}

		
		for(SortCondition sc : sortConditions) {
			query.addOrderBy(sc);
		}
		
		
		
		// Resolve paths
		{
			Template effectiveTemplate = template != null ? template : new Template(new BasicPattern());

			Query resolverConstruct = new Query();
			resolverConstruct.setQueryConstructType();
			resolverConstruct.setConstructTemplate(effectiveTemplate);
			resolverConstruct.setQueryPattern(effectivePattern);
			
			Resolver resolver = Resolvers.from((Var)rootVar, resolverConstruct);

			
			PathletJoinerImpl pathlet = new PathletJoinerImpl(resolver);
			// Add the base query to the pathlet, with variable ?s joining with the pathlet's root
			// and ?s also being the connector for subsequent joins
			pathlet.add(new PathletSimple((Var)rootVar, (Var)rootVar, new RelationletElementImpl(query.getQueryPattern()).fixAllVars()));
	
			// Substitute all NodePathletPath objects with NodePathletVarRef objects
			NodeTransform xform1 = new NodeTransformPathletPathResolver(pathlet);
			query = QueryUtils.applyNodeTransform(query, xform1);
	
			// Now that all paths have been collected and added to the pathlet
			// materalize it
			RelationletSimple rn = pathlet.materialize();
			
			// Resolve all var refs against the materialized relationlet
			NodeTransform xform2 = new NodeTransformPathletVarRefResolver(rn);
			query = QueryUtils.applyNodeTransform(query, xform2);
			
			Element e = rn.getElement();
	
			query.setQueryPattern(e);
		}
		
		
		
		
		//logger.info("Generated query: " + query);

		Rewrite rewrite = AlgebraUtils.createDefaultRewriter();
		query = QueryUtils.rewrite(query, rewrite::rewrite);

		// NOTE: The CONSTRUCT part gets lost in the rewriting, restore it 
		
		//if(canAsConstruct) {
		//}

		
		Query c = QueryUtils.selectToConstruct(query, template);

//		logger.debug("After rewrite: " + query);


		// Pattern p = Pattern.compile("^.*v_2\\s*<[^>]*>\\s*v_2.*$", Pattern.MULTILINE);
		// if(p.matcher("" + query).find()) {
		// 	System.out.println("DEBUG POINT reached");
		// }
		
		return Maps.immutableEntry((Var)rootVar, c);
	}


	@Override
	public Flowable<T> exec() {
		Objects.requireNonNull(conn);

		Entry<Node, Query> e = toConstructQuery();
//		Node rootVar = e.getKey();
//		Query query = e.getValue();
		
		// PseudoRandomness affects:
		// limit, offset, orderByRandom, ... what else?
		
		
		logger.debug("Executing query:\n" + e);
//		
//		Flowable<T> result = ReactiveSparqlUtils
//			// For future reference: If we get an empty results by using the query object, we probably have wrapped a variable with NodeValue.makeNode. 
//			.execSelect(() -> conn.query(query))
//			.map(b -> {
//				Graph graph = GraphFactory.createDefaultGraph();
//
//				// TODO Re-allocate blank nodes
//				if(template != null) {
//					Iterator<Triple> it = TemplateLib.calcTriples(template.getTriples(), Iterators.singletonIterator(b));
//					while(it.hasNext()) {
//						Triple t = it.next();
//						graph.add(t);
//					}
//				}
//
//				Node rootNode = rootVar.isVariable() ? b.get((Var)rootVar) : rootVar;
//				
//				Model m = ModelFactory.createModelForGraph(graph);
//				RDFNode r = m.asRDFNode(rootNode);
//				
////				Resource r = m.createResource()
////				.addProperty(RDF.predicate, m.asRDFNode(valueNode))
////				.addProperty(Vocab.facetValueCount, );
////			//m.wrapAsResource(valueNode);
////			return r;
//
//				return r;
//			})
		
		Flowable<T> result = SparqlRx.execPartitioned(conn, e)
			.map(r -> r.as(resultClass));
		
		
		boolean deterministic = pseudoRandom != null;

		if(deterministic && randomOrder) {
			result = result.toList().map(l -> {
				// Always sort the collection, so that subsequent shuffle will give the same result
				// regardless of initial order
				Collections.sort(l, (a, b) ->
					NodeValue.compareAlways(NodeValue.makeNode(a.asNode()), NodeValue.makeNode(b.asNode())));

				Collections.shuffle(l, pseudoRandom);
				
				Range<Long> available = Range.closed(0l, (long)l.size());
				Range<Long> requested = QueryUtils.toRange(offset, limit);
				Range<Long> effective = available.intersection(requested);
				long o = effective.lowerEndpoint();
				long size = effective.upperEndpoint() - o;
				
				List<T> subList = l.subList((int)o, (int)size);
				
				return subList;
			}).toFlowable().flatMap(Flowable::fromIterable);
		}
		
		return result;
	}
	

	/**
	 * A template resolver enables traversal of the template and injection of
	 * further triple patterns.
	 * 
	 * So essentially it is the Resource API with the addition of allowing DataResolution
	 * references.
	 *
	 * 
	 */
//	public TemplateResolver templateResolver() {
//		
//	}
	
	
	public DataQuery<T> addOrderBy(Node node, int direction) {
		sortConditions.add(new SortCondition(node, direction));
		return this;
	}

	public DataQuery<T> addOrderBy(Path path, int direction) {
		return addOrderBy(new NodePathletPath(path), direction);
	}

	
//	public void addSortCondition() {
//		Query x;
//		x.orde
//		sortConditions.add(e)
//	}
	
	@Override
	public Relation baseRelation() {
//		Element effectivePattern = filter == null
//				? baseQueryPattern
//				: new RelationImpl(baseQueryPattern, new ArrayList<>(PatternVars.vars(baseQueryPattern))).joinOn((Var)rootVar).with(filter).getElement()
//				;

		//UnaryRelation result = new Concept(baseQueryPattern, (Var)rootVar);
		return baseRelation;
	}

	@Override
	public Single<Model> execConstruct() {
		return exec().toList().map(l -> {
			Model r = ModelFactory.createDefaultModel();
			for(RDFNode item : l) {
				Model tmp = item.getModel();
				if(tmp != null) {
					r.add(tmp);
				}
			}
			return r;
		});
	}	

	
	// TODO Move to Query Utils
//	public static Query rewrite(Query query, Rewrite rewrite) {
//		Query result = rewrite(query, (Function<? super Op, ? extends Op>)rewrite::rewrite);
//		result.getPrefixMapping().setNsPrefixes(query.getPrefixMapping());
//		return result;
//	}


	@Override
	public Single<CountInfo> count() {
		return count(null, null);
	}

	@Override
	public Single<CountInfo> count(Long distinctItemCount, Long rowCount) {
		Entry<Node, Query> e = toConstructQuery();
		Query query = e.getValue();
		//		QueryExecutionUtils.countQuery(query, new QueryExecutionFactorySparqlQueryConnection(conn));
		Single<CountInfo> result = SparqlRx.fetchCountQuery(conn, query, distinctItemCount, rowCount)
				.map(range -> CountUtils.toCountInfo(range));
		
		return result;
	}

	@Override
	public ResolverNode resolver() {
		Entry<Node, Query> e = toConstructQuery();
		PartitionedQuery1 pq = PartitionedQuery1.from(e.getValue(), (Var)e.getKey());
		
		ResolverNode result = ResolverNodeImpl.from(pq, this);
		return result;
	}

	
}

