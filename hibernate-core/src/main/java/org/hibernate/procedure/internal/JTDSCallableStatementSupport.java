/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.procedure.internal;

import java.util.List;

import org.hibernate.QueryException;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.procedure.spi.FunctionReturnImplementor;
import org.hibernate.procedure.spi.ProcedureCallImplementor;
import org.hibernate.procedure.spi.ProcedureParameterImplementor;
import org.hibernate.query.spi.ProcedureParameterMetadataImplementor;
import org.hibernate.sql.exec.internal.JdbcCallImpl;
import org.hibernate.sql.exec.spi.JdbcCallParameterRegistration;
import org.hibernate.sql.exec.spi.JdbcOperationQueryCall;

import jakarta.persistence.ParameterMode;

/**
 * Special implementation of CallableStatementSupport for the jTDS driver.
 * Apparently, jTDS doesn't like the JDBC standard named parameter notation with the ':' prefix,
 * and instead requires that we render this as `@param=?`.
 */
public class JTDSCallableStatementSupport extends AbstractStandardCallableStatementSupport {
	public static final JTDSCallableStatementSupport INSTANCE = new JTDSCallableStatementSupport();

	@Override
	public JdbcOperationQueryCall interpretCall(ProcedureCallImplementor<?> procedureCall) {
		final String procedureName = procedureCall.getProcedureName();
		final FunctionReturnImplementor<?> functionReturn = procedureCall.getFunctionReturn();
		final ProcedureParameterMetadataImplementor parameterMetadata = procedureCall.getParameterMetadata();
		final SharedSessionContractImplementor session = procedureCall.getSession();
		final List<? extends ProcedureParameterImplementor<?>> registrations = parameterMetadata.getRegistrationsAsList();
		final int paramStringSizeEstimate;
		if ( functionReturn == null && parameterMetadata.hasNamedParameters() ) {
			// That's just a rough estimate. I guess most params will have fewer than 8 chars on average
			paramStringSizeEstimate = registrations.size() * 12;
		}
		else {
			// For every param rendered as '?' we have a comma, hence the estimate
			paramStringSizeEstimate = registrations.size() * 2;
		}
		final JdbcCallImpl.Builder builder = new JdbcCallImpl.Builder();
		final StringBuilder buffer;
		final int offset;
		if ( functionReturn != null ) {
			offset = 2;
			buffer = new StringBuilder( 11 + procedureName.length() + paramStringSizeEstimate ).append( "{?=call " );
			builder.setFunctionReturn( functionReturn.toJdbcFunctionReturn( session ) );
		}
		else {
			offset = 1;
			buffer = new StringBuilder( 9 + procedureName.length() + paramStringSizeEstimate ).append( "{call " );
		}

		buffer.append( procedureName );

		if ( registrations.isEmpty() ) {
			buffer.append( '(' );
		}
		else {
			char sep = '(';
			for ( int i = 0; i < registrations.size(); i++ ) {
				final ProcedureParameterImplementor<?> parameter = registrations.get( i );
				if ( parameter.getMode() == ParameterMode.REF_CURSOR ) {
					throw new QueryException( "Dialect [" + session.getJdbcServices().getJdbcEnvironment().getDialect().getClass().getName() + "] not known to support REF_CURSOR parameters" );
				}
				buffer.append( sep );
				final JdbcCallParameterRegistration registration = parameter.toJdbcParameterRegistration(
						i + offset,
						procedureCall
				);
				if ( registration.getName() != null ) {
					buffer.append( '@' ).append( registration.getName() ).append( "=?" );
				}
				else {
					buffer.append( "?" );
				}
				sep = ',';
				builder.addParameterRegistration( registration );
			}
		}

		buffer.append( ")}" );

		builder.setCallableName( buffer.toString() );
		return builder.buildJdbcCall();
	}
}
