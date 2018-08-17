package org.hobbit.benchmark.faceted_browsing.v2.vocab;

import java.util.EmptyStackException;
import java.util.Iterator;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.aksw.commons.collections.SinglePrefetchIterator;
import org.aksw.facete.v3.impl.ResourceBase;
import org.aksw.jena_sparql_api.utils.model.ResourceUtils;
import org.apache.jena.enhanced.EnhGraph;
import org.apache.jena.ext.com.google.common.collect.Iterables;
import org.apache.jena.graph.Node;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.hobbit.benchmark.faceted_browsing.v2.domain.Vocab;


class LinkedIterator<X, T>
	extends SinglePrefetchIterator<T>
{
	protected X current;
	protected Supplier<? extends X> first;
	protected Function<? super X, ? extends X > next;
	protected Function<X, T> value;
	protected Consumer<? super X> remove;

	public LinkedIterator(
			Supplier<? extends X> first,
			Function<? super X, ? extends X> next,
			Function<X, T> value,
			Consumer<? super X> remove) {
		super();
		this.current = null; //current;
		
		this.first = first;
		this.next = next;
		this.value = value;
		this.remove = remove;
	}

	@Override
	protected T prefetch() throws Exception {
		current = current == null ? first.get() : next.apply(current);
		
		T result = current == null
				? finish()
				: value.apply(current);
		
		return result;
	}
	
	@Override
	protected void doRemove() {
		if(remove != null) {
			remove.accept(current);
		} else {
			throw new UnsupportedOperationException("Cannot remove item as no removal consumer is available");
		}
	}
	
}

public class StackImpl
	extends ResourceBase
	implements Stack
{
	public StackImpl(Node n, EnhGraph m) {
		super(n, m);
	}

	public void push(RDFNode item) {
		pushStackItem(this, item);
	}
	
	public RDFNode pop() {
		RDFNode result = pop(this);
		return result;
	}

	public Iterator<RDFNode> iterator() {
		return new LinkedIterator<Resource, RDFNode>(
				() -> ResourceUtils.getPropertyValue(this, Vocab.last, Resource.class).orElse(null),
				n -> ResourceUtils.getPropertyValue(n, Vocab.prior, Resource.class).orElse(null),
				n -> ResourceUtils.getPropertyValue(n, Vocab.value).orElse(null),
				null);
	}
	
	
	public static RDFNode pop(Resource stack) {
		Resource holder = ResourceUtils.getPropertyValue(stack, Vocab.last).map(RDFNode::asResource).orElse(null);

		RDFNode result = null;
		if(holder != null) {
			result = ResourceUtils.getPropertyValue(holder, Vocab.value).orElse(null);
		
			RDFNode prior = ResourceUtils.getPropertyValue(holder, Vocab.prior).orElse(null);

			ResourceUtils.setProperty(stack, Vocab.last, prior);
		} else {
			throw new EmptyStackException();
		}
		
		return result;
	}
	
	public static void pushStackItem(Resource stack, RDFNode item) {		
		Model m = stack.getModel();
//		if(item.getModel() != m) {
//			item = item.inModel(m);
//			if(item.isResource()) {
//				m.add(org.apache.jena.util.ResourceUtils.reachableClosure(item.asResource()));
//			}
//		}

		Resource holder = m.createResource();
		holder.addProperty(Vocab.value, item);
		
		RDFNode prior = ResourceUtils.getPropertyValue(stack, Vocab.last).orElse(null);
		ResourceUtils.setProperty(stack, Vocab.last, holder);
		ResourceUtils.setProperty(holder, Vocab.prior, prior);		
	}
	
	@Override
	public String toString() {
		return "Stack " +  node + ": " + Iterables.toString(this);
	}

}
