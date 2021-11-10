/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or http://www.gnu.org/licenses/lgpl-2.1.html
 */
package org.hibernate.query.sqm.tree.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import jakarta.persistence.criteria.Fetch;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.metamodel.CollectionAttribute;
import jakarta.persistence.metamodel.ListAttribute;
import jakarta.persistence.metamodel.MapAttribute;
import jakarta.persistence.metamodel.PluralAttribute;
import jakarta.persistence.metamodel.SetAttribute;
import jakarta.persistence.metamodel.SingularAttribute;

import org.hibernate.metamodel.model.domain.BagPersistentAttribute;
import org.hibernate.metamodel.model.domain.EntityDomainType;
import org.hibernate.metamodel.model.domain.ListPersistentAttribute;
import org.hibernate.metamodel.model.domain.ManagedDomainType;
import org.hibernate.metamodel.model.domain.MapPersistentAttribute;
import org.hibernate.metamodel.model.domain.PluralPersistentAttribute;
import org.hibernate.metamodel.model.domain.SetPersistentAttribute;
import org.hibernate.metamodel.model.domain.SingularPersistentAttribute;
import org.hibernate.query.NavigablePath;
import org.hibernate.query.criteria.JpaEntityJoin;
import org.hibernate.query.criteria.JpaPath;
import org.hibernate.query.criteria.JpaSelection;
import org.hibernate.query.sqm.NodeBuilder;
import org.hibernate.query.SemanticException;
import org.hibernate.query.sqm.SqmPathSource;
import org.hibernate.query.sqm.UnknownPathException;
import org.hibernate.query.hql.spi.SqmCreationState;
import org.hibernate.query.sqm.spi.SqmCreationHelper;
import org.hibernate.query.sqm.tree.SqmJoinType;
import org.hibernate.query.sqm.tree.from.SqmAttributeJoin;
import org.hibernate.query.sqm.tree.from.SqmEntityJoin;
import org.hibernate.query.sqm.tree.from.SqmFrom;
import org.hibernate.query.sqm.tree.from.SqmJoin;
import org.hibernate.query.sqm.tree.from.SqmRoot;

/**
 * Convenience base class for SqmFrom implementations
 *
 * @author Steve Ebersole
 */
public abstract class AbstractSqmFrom<O,T> extends AbstractSqmPath<T> implements SqmFrom<O,T> {
	private String alias;

	private List<SqmJoin<T, ?>> joins;
	private List<SqmFrom<?, ?>> treats;

	protected AbstractSqmFrom(
			NavigablePath navigablePath,
			SqmPathSource<T> referencedNavigable,
			SqmFrom<?, ?> lhs,
			String alias,
			NodeBuilder nodeBuilder) {
		super( navigablePath, referencedNavigable, lhs, nodeBuilder );

		if ( lhs == null ) {
			throw new IllegalArgumentException( "LHS cannot be null" );
		}
		this.alias = alias;
	}

	/**
	 * Intended for use with {@link SqmRoot}
	 */
	protected AbstractSqmFrom(
			EntityDomainType<T> entityType,
			String alias,
			NodeBuilder nodeBuilder) {
		super(
				SqmCreationHelper.buildRootNavigablePath( entityType.getHibernateEntityName(), alias ),
				entityType,
				null,
				nodeBuilder
		);

		this.alias = alias;
	}

	/**
	 * Intended for use with {@link SqmTreatedRoot} -> {@link SqmRoot}
	 */
	protected AbstractSqmFrom(
			NavigablePath navigablePath,
			EntityDomainType<T> entityType,
			String alias,
			NodeBuilder nodeBuilder) {
		super( navigablePath, entityType, null, nodeBuilder );

		this.alias = alias;
	}

	/**
	 * Intended for use with {@link SqmCorrelatedRootJoin} through {@link SqmRoot}
	 */
	protected AbstractSqmFrom(
			NavigablePath navigablePath,
			SqmPathSource<T> referencedNavigable,
			NodeBuilder nodeBuilder) {
		super( navigablePath, referencedNavigable, null, nodeBuilder );
	}

	@Override
	public String getExplicitAlias() {
		return alias;
	}

	@Override
	public void setExplicitAlias(String explicitAlias) {
		this.alias = explicitAlias;
	}

	@Override
	public SqmPath<?> resolvePathPart(
			String name,
			boolean isTerminal,
			SqmCreationState creationState) {
		final NavigablePath subNavPath = getNavigablePath().append( name );
		return creationState.getProcessingStateStack().getCurrent().getPathRegistry().resolvePath(
				subNavPath,
				snp -> {
					final SqmPathSource<?> subSource = getReferencedPathSource().findSubPathSource( name );
					if ( subSource == null ) {
						throw UnknownPathException.unknownSubPath( this, name );
					}

					return subSource.createSqmPath( this, getReferencedPathSource().getIntermediatePathSource( subSource ) );
				}
		);
	}

	@Override
	public boolean hasJoins() {
		return !( joins == null || joins.isEmpty() );
	}

	@Override
	public List<SqmJoin<T, ?>> getSqmJoins() {
		return joins == null ? Collections.emptyList() : Collections.unmodifiableList( joins );
	}

	@Override
	public void addSqmJoin(SqmJoin<T, ?> join) {
		if ( joins == null ) {
			joins = new ArrayList<>();
		}
		joins.add( join );
		findRoot().addOrderedJoin( join );
	}

	@Override
	public void visitSqmJoins(Consumer<SqmJoin<T, ?>> consumer) {
		if ( joins != null ) {
			joins.forEach( consumer );
		}
	}

	@Override
	public boolean hasTreats() {
		return treats != null && !treats.isEmpty();
	}

	@Override
	public List<SqmFrom<?, ?>> getSqmTreats() {
		return treats == null ? Collections.emptyList() : treats;
	}

	protected <S, X extends SqmFrom<?, S>> X findTreat(EntityDomainType<S> targetType, String alias) {
		if ( treats != null ) {
			for ( SqmFrom<?, ?> treat : treats ) {
				if ( treat.getModel() == targetType ) {
					if ( treat.getExplicitAlias() == null && alias == null
							|| Objects.equals( treat.getExplicitAlias(), alias ) ) {
						//noinspection unchecked
						return (X) treat;
					}
				}
			}
		}
		return null;
	}

	protected <X extends SqmFrom<?, ?>> X addTreat(X treat) {
		if ( treats == null ) {
			treats = new ArrayList<>();
		}
		treats.add( treat );
		return treat;
	}

	// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
	// JPA


	@Override
	public JpaPath<?> getParentPath() {
		return getLhs();
	}

	@Override
	public SqmFrom<O,T> getCorrelationParent() {
		throw new IllegalStateException( "Not correlated" );
	}

	public abstract SqmCorrelation<O, T> createCorrelation();

	@Override
	public boolean isCorrelated() {
		return false;
	}

	@Override
	public Set<Join<T, ?>> getJoins() {
		//noinspection unchecked
		return (Set<Join<T, ?>>) (Set<?>) getSqmJoins().stream()
				.filter( sqmJoin -> ! ( sqmJoin instanceof SqmAttributeJoin && ( (SqmAttributeJoin<?, ?>) sqmJoin ).isFetched() ) )
				.collect( Collectors.toSet() );
	}

	@Override
	public <A> SqmSingularJoin<T, A> join(SingularAttribute<? super T, A> attribute) {
		return join( attribute, JoinType.INNER );
	}

	@Override
	@SuppressWarnings("unchecked")
	public <A> SqmSingularJoin<T, A> join(SingularAttribute<? super T, A> attribute, JoinType jt) {
		final SqmSingularJoin<T, A> join = buildSingularJoin( (SingularPersistentAttribute<? super T, A>) attribute, SqmJoinType.from( jt ), false );
		addSqmJoin( join );
		return join;
	}

	@Override
	public <A> SqmBagJoin<T, A> join(CollectionAttribute<? super T, A> attribute) {
		return join( attribute, JoinType.INNER );
	}

	@Override
	@SuppressWarnings("unchecked")
	public <E> SqmBagJoin<T, E> join(CollectionAttribute<? super T, E> attribute, JoinType jt) {
		final SqmBagJoin<T, E> join = buildBagJoin( (BagPersistentAttribute<T, E>) attribute, SqmJoinType.from( jt ), false );
		addSqmJoin( join );
		return join;
	}

	@Override
	public <E> SqmSetJoin<T, E> join(SetAttribute<? super T, E> attribute) {
		return join( attribute, JoinType.INNER );
	}

	@Override
	@SuppressWarnings("unchecked")
	public <E> SqmSetJoin<T, E> join(SetAttribute<? super T, E> attribute, JoinType jt) {
		final SqmSetJoin<T, E> join = buildSetJoin(
				(SetPersistentAttribute<? super T, E>) attribute,
				SqmJoinType.from( jt ),
				false
		);
		addSqmJoin( join );
		return join;
	}

	@Override
	public <E> SqmListJoin<T, E> join(ListAttribute<? super T, E> attribute) {
		return join( attribute, JoinType.INNER );
	}

	@Override
	@SuppressWarnings("unchecked")
	public <E> SqmListJoin<T, E> join(ListAttribute<? super T, E> attribute, JoinType jt) {
		final SqmListJoin<T, E> join = buildListJoin(
				(ListPersistentAttribute<? super T, E>) attribute,
				SqmJoinType.from( jt ),
				false
		);
		addSqmJoin( join );
		return join;
	}

	@Override
	public <K, V> SqmMapJoin<T, K, V> join(MapAttribute<? super T, K, V> attribute) {
		return join( attribute, JoinType.INNER );
	}

	@Override
	@SuppressWarnings("unchecked")
	public <K, V> SqmMapJoin<T, K, V> join(MapAttribute<? super T, K, V> attribute, JoinType jt) {
		final SqmMapJoin<T, K, V> join = buildMapJoin(
				(MapPersistentAttribute<? super T, K, V>) attribute,
				SqmJoinType.from( jt ),
				false
		);
		addSqmJoin( join );
		return join;
	}

	@Override
	public <X, Y> SqmAttributeJoin<X, Y> join(String attributeName) {
		return join( attributeName, JoinType.INNER );
	}

	@Override
	@SuppressWarnings("unchecked")
	public <X, Y> SqmAttributeJoin<X, Y> join(String attributeName, JoinType jt) {
		final SqmPathSource<?> subPathSource = getReferencedPathSource().findSubPathSource( attributeName );
		return (SqmAttributeJoin<X, Y>) buildJoin( subPathSource, SqmJoinType.from( jt ), false );
	}

	@Override
	public <X, Y> SqmBagJoin<X, Y> joinCollection(String attributeName) {
		return joinCollection( attributeName, JoinType.INNER );
	}

	@Override
	@SuppressWarnings("unchecked")
	public <X, Y> SqmBagJoin<X, Y> joinCollection(String attributeName, JoinType jt) {
		final SqmPathSource<?> joinedPathSource = getReferencedPathSource().findSubPathSource( attributeName );

		if ( joinedPathSource instanceof BagPersistentAttribute ) {
			final SqmBagJoin<T, Y> join = buildBagJoin(
					(BagPersistentAttribute<T, Y>) joinedPathSource,
					SqmJoinType.from( jt ),
					false
			);
			addSqmJoin( join );
			return (SqmBagJoin<X, Y>) join;
		}

		throw new IllegalArgumentException(
				String.format(
						Locale.ROOT,
						"Passed attribute name [%s] did not correspond to a collection (bag) reference [%s] relative to %s",
						attributeName,
						joinedPathSource,
						getNavigablePath().getFullPath()
				)
		);
	}

	@Override
	public <X, Y> SqmSetJoin<X, Y> joinSet(String attributeName) {
		return joinSet( attributeName, JoinType.INNER );
	}

	@Override
	@SuppressWarnings("unchecked")
	public <X, Y> SqmSetJoin<X, Y> joinSet(String attributeName, JoinType jt) {
		final SqmPathSource<?> joinedPathSource = getReferencedPathSource().findSubPathSource( attributeName );

		if ( joinedPathSource instanceof SetPersistentAttribute ) {
			final SqmSetJoin<T, Y> join = buildSetJoin(
					(SetPersistentAttribute<T, Y>) joinedPathSource,
					SqmJoinType.from( jt ),
					false
			);
			addSqmJoin( join );
			return (SqmSetJoin<X, Y>) join;
		}

		throw new IllegalArgumentException(
				String.format(
						Locale.ROOT,
						"Passed attribute name [%s] did not correspond to a collection (set) reference [%s] relative to %s",
						attributeName,
						joinedPathSource,
						getNavigablePath().getFullPath()
				)
		);
	}

	@Override
	public <X, Y> SqmListJoin<X, Y> joinList(String attributeName) {
		return joinList( attributeName, JoinType.INNER );
	}

	@Override
	@SuppressWarnings("unchecked")
	public <X, Y> SqmListJoin<X, Y> joinList(String attributeName, JoinType jt) {
		final SqmPathSource<?> joinedPathSource = getReferencedPathSource().findSubPathSource( attributeName );

		if ( joinedPathSource instanceof ListPersistentAttribute ) {
			final SqmListJoin<T, Y> join = buildListJoin(
					(ListPersistentAttribute<T, Y>) joinedPathSource,
					SqmJoinType.from( jt ),
					false
			);
			addSqmJoin( join );
			return (SqmListJoin<X, Y>) join;
		}

		throw new IllegalArgumentException(
				String.format(
						Locale.ROOT,
						"Passed attribute name [%s] did not correspond to a collection (list) reference [%s] relative to %s",
						attributeName,
						joinedPathSource,
						getNavigablePath().getFullPath()
				)
		);
	}

	@Override
	public <X, K, V> SqmMapJoin<X, K, V> joinMap(String attributeName) {
		return joinMap( attributeName, JoinType.INNER );
	}

	@Override
	@SuppressWarnings("unchecked")
	public <X, K, V> SqmMapJoin<X, K, V> joinMap(String attributeName, JoinType jt) {
		final SqmPathSource<?> joinedPathSource = getReferencedPathSource().findSubPathSource( attributeName );

		if ( joinedPathSource instanceof MapPersistentAttribute<?, ?, ?> ) {
			final SqmMapJoin<T, K, V> join = buildMapJoin(
					(MapPersistentAttribute<T, K, V>) joinedPathSource,
					SqmJoinType.from( jt ),
					false
			);
			addSqmJoin( join );
			return (SqmMapJoin<X, K, V>) join;
		}

		throw new IllegalArgumentException(
				String.format(
						Locale.ROOT,
						"Passed attribute name [%s] did not correspond to a collection (map) reference [%s] relative to %s",
						attributeName,
						joinedPathSource,
						getNavigablePath().getFullPath()
				)
		);
	}

	@Override
	public <X> JpaEntityJoin<X> join(Class<X> entityJavaType) {
		return join( nodeBuilder().getDomainModel().entity( entityJavaType ) );
	}

	@Override
	public <X> JpaEntityJoin<X> join(EntityDomainType<X> entity) {
		return join( entity, SqmJoinType.INNER );
	}

	@Override
	public <X> JpaEntityJoin<X> join(Class<X> entityJavaType, SqmJoinType joinType) {
		return join( nodeBuilder().getDomainModel().entity( entityJavaType ), joinType );
	}

	@Override
	public <X> JpaEntityJoin<X> join(EntityDomainType<X> entity, SqmJoinType joinType) {
		final SqmEntityJoin<X> join = new SqmEntityJoin<>( entity, null, joinType, findRoot() );
		//noinspection unchecked
		addSqmJoin( (SqmJoin<T, ?>) join );
		return join;
	}

	@Override
	public Set<Fetch<T, ?>> getFetches() {
		//noinspection unchecked
		return (Set<Fetch<T, ?>>) (Set<?>) getSqmJoins().stream()
				.filter( sqmJoin -> sqmJoin instanceof SqmAttributeJoin && ( (SqmAttributeJoin<?, ?>) sqmJoin ).isFetched() )
				.collect( Collectors.toSet() );
	}

	@Override
	public <A> SqmSingularJoin<T,A> fetch(SingularAttribute<? super T, A> attribute) {
		return fetch( attribute, JoinType.INNER );
	}

	@Override
	@SuppressWarnings("unchecked")
	public <A> SqmSingularJoin<T, A> fetch(SingularAttribute<? super T, A> attribute, JoinType jt) {
		final SqmSingularJoin<T, A> join = buildSingularJoin(
				(SingularPersistentAttribute<? super T, A>) attribute,
				SqmJoinType.from( jt ),
				true
		);
		addSqmJoin( join );
		return join;
	}

	@Override
	public <A> SqmAttributeJoin<T, A> fetch(PluralAttribute<? super T, ?, A> attribute) {
		return fetch( attribute, JoinType.INNER );
	}

	@Override
	@SuppressWarnings("unchecked")
	public <A> SqmAttributeJoin<T, A> fetch(PluralAttribute<? super T, ?, A> attribute, JoinType jt) {
		return buildJoin(
				(PluralPersistentAttribute<? super T, ?, A>) attribute,
				SqmJoinType.from( jt ),
				true
		);
	}

	@Override
	public <X,A> SqmAttributeJoin<X,A> fetch(String attributeName) {
		return fetch( attributeName, JoinType.INNER );
	}

	@Override
	@SuppressWarnings("unchecked")
	public <X, A> SqmAttributeJoin<X, A> fetch(String attributeName, JoinType jt) {
		final SqmPathSource<A> fetchedPathSource = (SqmPathSource<A>) getReferencedPathSource()
				.findSubPathSource( attributeName );
		return (SqmAttributeJoin<X, A>) buildJoin(
				fetchedPathSource,
				SqmJoinType.from( jt ),
				true
		);
	}

	private <A> SqmAttributeJoin<T, A> buildJoin(
			SqmPathSource<A> joinedPathSource,
			SqmJoinType joinType,
			boolean fetched) {
		final SqmAttributeJoin<T, A> sqmJoin;
		if ( joinedPathSource instanceof SingularPersistentAttribute<?, ?> ) {
			sqmJoin = buildSingularJoin(
					(SingularPersistentAttribute<T, A>) joinedPathSource,
					joinType,
					fetched
			);
		}
		else if ( joinedPathSource instanceof BagPersistentAttribute<?, ?> ) {
			sqmJoin = buildBagJoin(
					(BagPersistentAttribute<T, A>) joinedPathSource,
					joinType,
					fetched
			);
		}
		else if ( joinedPathSource instanceof ListPersistentAttribute<?, ?> ) {
			sqmJoin = buildListJoin(
					(ListPersistentAttribute<T, A>) joinedPathSource,
					joinType,
					fetched
			);
		}
		else if ( joinedPathSource instanceof MapPersistentAttribute<?, ?, ?> ) {
			sqmJoin = buildMapJoin(
					(MapPersistentAttribute<T, ?, A>) joinedPathSource,
					joinType,
					fetched
			);
		}
		else if ( joinedPathSource instanceof SetPersistentAttribute<?, ?> ) {
			sqmJoin = buildSetJoin(
					(SetPersistentAttribute<T, A>) joinedPathSource,
					joinType,
					fetched
			);
		}
		else {
			throw new IllegalArgumentException(
					String.format(
							Locale.ROOT,
							"Passed attribute [%s] did not correspond to a joinable reference [%s] relative to %s",
							joinedPathSource.getPathName(),
							joinedPathSource,
							getNavigablePath().getFullPath()
					)
			);
		}
		addSqmJoin( sqmJoin );
		return sqmJoin;
	}

	@SuppressWarnings("unchecked")
	private <A> SqmSingularJoin<T, A> buildSingularJoin(
			SingularPersistentAttribute<? super T, A> attribute,
			SqmJoinType joinType,
			boolean fetched) {
		if ( attribute.getSqmPathType() instanceof ManagedDomainType ) {
			return new SqmSingularJoin<>(
					this,
					(SingularPersistentAttribute<T, A>) attribute,
					null,
					joinType,
					fetched,
					nodeBuilder()
			);
		}

		throw new SemanticException( "Attribute [" + attribute + "] is not joinable" );
	}

	@SuppressWarnings("unchecked")
	private <E> SqmBagJoin<T, E> buildBagJoin(
			BagPersistentAttribute<? super T, E> attribute,
			SqmJoinType joinType,
			boolean fetched) {
		return new SqmBagJoin<>(
				this,
				(BagPersistentAttribute<T, E>)attribute,
				null,
				joinType,
				fetched,
				nodeBuilder()
		);
	}

	@SuppressWarnings("unchecked")
	private <E> SqmListJoin<T, E> buildListJoin(
			ListPersistentAttribute<? super T, E> attribute,
			SqmJoinType joinType,
			boolean fetched) {
		return new SqmListJoin<>(
				this,
				(ListPersistentAttribute<T, E>) attribute,
				null,
				joinType,
				fetched,
				nodeBuilder()
		);
	}

	@SuppressWarnings("unchecked")
	private <K, V> SqmMapJoin<T, K, V> buildMapJoin(
			MapPersistentAttribute<? super T, K, V> attribute,
			SqmJoinType joinType,
			boolean fetched) {
		return new SqmMapJoin<>(
				this,
				(MapPersistentAttribute<T, K, V>) attribute,
				null,
				joinType,
				fetched,
				nodeBuilder()
		);
	}

	@SuppressWarnings("unchecked")
	private <E> SqmSetJoin<T, E> buildSetJoin(
			SetPersistentAttribute<? super T, E> attribute,
			SqmJoinType joinType,
			boolean fetched) {
		return new SqmSetJoin<>(
				this,
				(SetPersistentAttribute<T, E>) attribute,
				null,
				joinType,
				fetched,
				nodeBuilder()
		);
	}

	@Override
	public void appendHqlString(StringBuilder sb) {
		if ( alias == null ) {
			// If we don't have an alias, this is the best we can do to at least ensure uniqueness
			sb.append( "alias_" ).append( System.identityHashCode( this ) );
		}
		else {
			sb.append( alias );
		}
	}

	@Override
	public JpaSelection<T> alias(String name) {
		if ( getExplicitAlias() == null ) {
			setExplicitAlias( name );
		}
		return super.alias( name );
	}
}
