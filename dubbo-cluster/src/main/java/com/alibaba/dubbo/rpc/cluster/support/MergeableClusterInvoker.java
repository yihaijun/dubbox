/*
 * Copyright 1999-2011 Alibaba Group.
 *  
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *  
 *      http://www.apache.org/licenses/LICENSE-2.0
 *  
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.dubbo.rpc.cluster.support;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.utils.NamedThreadFactory;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.RpcInvocation;
import com.alibaba.dubbo.rpc.RpcResult;
import com.alibaba.dubbo.rpc.cluster.Directory;
import com.alibaba.dubbo.rpc.cluster.merger.ArrayMerger;
import com.alibaba.dubbo.rpc.cluster.merger.ListMerger;
import com.alibaba.dubbo.rpc.cluster.merger.MapMerger;
import com.alibaba.dubbo.rpc.cluster.merger.SetMerger;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * @author <a href="mailto:gang.lvg@alibaba-inc.com">kimi</a>
 */
public class MergeableClusterInvoker<T> implements Invoker<T> {

    private ExecutorService executor = Executors.newCachedThreadPool(new NamedThreadFactory("mergeable-cluster-executor", true));
    
    private final Directory<T> directory;

    public MergeableClusterInvoker(Directory<T> directory) {
        this.directory = directory;
    }

    @SuppressWarnings("unchecked")
    public Result invoke(final Invocation invocation) throws RpcException {
        int timeout = getUrl().getMethodParameter( invocation.getMethodName(), Constants.TIMEOUT_KEY, Constants.DEFAULT_TIMEOUT );
        List<Invoker<T>> invokers = directory.list(invocation);
        
        Map<String, Future<Result>> results = new HashMap<String, Future<Result>>();
        for( final Invoker<T> invoker : invokers ) {
            Future<Result> future = executor.submit( new Callable<Result>() {
                public Result call() throws Exception {
                    return invoker.invoke(new RpcInvocation(invocation, invoker.getUrl()));
                }
            } );
            results.put( invoker.getUrl().getServiceKey(), future );
        }

        Object result = null;
        Class<?> returnType;
        try {
            returnType = getInterface().getMethod(
                    invocation.getMethodName(), invocation.getParameterTypes() ).getReturnType();
        } catch ( NoSuchMethodException e ) {
            throw new RpcException( e.getMessage(), e );
        }

        List<Result> resultList = new ArrayList<Result>( results.size() );
        
        for ( Map.Entry<String, Future<Result>> entry : results.entrySet() ) {
            Future<Result> future = entry.getValue();
            try {
                resultList.add( future.get( timeout, TimeUnit.MILLISECONDS ) );
            } catch ( Exception e ) {
                throw new RpcException( new StringBuilder( 32 )
                                                .append( "Failed to invoke service " )
                                                .append( entry.getKey() )
                                                .append( ": " )
                                                .append( e.getMessage() ).toString(),
                                        e );
            }
        }

        if ( returnType != void.class && resultList.size() > 0 ) {
            String merger = getUrl().getMethodParameter( invocation.getMethodName(), Constants.MERGER_KEY );
            if ( merger != null && !"".equals( merger.trim() ) ) {
                Method method;
                try {
                    method = returnType.getMethod( merger, returnType );
                } catch ( NoSuchMethodException e ) {
                    throw new RpcException( new StringBuilder( 32 )
                                                    .append( "Can not merge result because missing method [ " )
                                                    .append( merger )
                                                    .append( " ] in class [ " )
                                                    .append( returnType.getClass().getName() )
                                                    .append( " ]" )
                                                    .toString() );
                }
                if ( method != null ) {
                    if ( !Modifier.isPublic( method.getModifiers() ) ) {
                        method.setAccessible( true );
                    }
                    result = resultList.remove( 0 ).getResult();
                    try {
                        if ( method.getReturnType() != void.class
                                && method.getReturnType().isAssignableFrom( result.getClass() ) ) {
                            for ( Result r : resultList ) {
                                result = method.invoke( result, r.getResult() );
                            }
                        } else {
                            for ( Result r : resultList ) {
                                method.invoke( result, r.getResult() );
                            }
                        }
                    } catch ( Exception e ) {
                        throw new RpcException( 
                                new StringBuilder( 32 )
                                        .append( "Can not merge result: " )
                                        .append( e.getMessage() ).toString(), 
                                e );
                    }
                } else {
                    throw new RpcException(
                            new StringBuilder( 32 )
                                    .append( "Can not merge result because missing method [ " )
                                    .append( merger )
                                    .append( " ] in class [ " )
                                    .append( returnType.getClass().getName() )
                                    .append( " ]" )
                                    .toString() );
                }
            } else if ( List.class.isAssignableFrom( returnType ) ) {
                List<List> args = new ArrayList<List>();
                for( Result r : resultList ) {
                    args.add( ( List ) r.getResult() );
                }
                result = ListMerger.INSTANCE.merge( args.toArray( new List[ args.size() ] ) );
            } else if ( Set.class.isAssignableFrom( returnType ) ) {
                List<Set> args = new ArrayList<Set>();
                for( Result r : resultList ) {
                    args.add( ( Set ) r.getResult() );
                }
                result = SetMerger.INSTANCE.merge( args.toArray( new Set[args.size()] ) );
            } else if ( Map.class.isAssignableFrom( returnType ) ) {
                List<Map> args = new ArrayList<Map>();
                for( Result r : resultList ) {
                    args.add( ( Map ) r.getResult() );
                }
                result = MapMerger.INSTANCE.merge( args.toArray( new Map[args.size()] ) );
            } else if ( returnType.isArray() ) {
                List<Object> args = new ArrayList<Object>();
                for( Result r : resultList ) {
                    args.add( r.getResult() );
                }
                result = ArrayMerger.INSTANCE.merge( args.toArray( new Object[args.size()] ) );
            } else {
                throw new RpcException( "There is no merger to merge result." );
            }
        }

        return new RpcResult( result );
    }

    public Class<T> getInterface() {
        return directory.getInterface();
    }

    public URL getUrl() {
        return directory.getUrl();
    }

    public boolean isAvailable() {
        return directory.isAvailable();
    }

    public void destroy() {
        directory.destroy();
    }

}