package com.wealoha.thrift;

import java.util.List;
import java.util.stream.Collectors;

import org.apache.commons.lang3.RandomUtils;
import org.apache.commons.pool2.BasePooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.TTransport;
import org.apache.thrift.transport.TTransportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.wealoha.thrift.exception.ConnectionFailException;
import com.wealoha.thrift.exception.NoBackendServiceException;
import com.wealoha.thrift.exception.ThriftException;

/**
 * Pool for ThriftClient <br/>
 * 
 * <code>
 * ThriftClientPool pool = new ThriftClientPool(services, clientFactory)
 * </code>
 * 
 * @author javamonk
 * @createTime 2014年7月4日 下午3:55:16
 */
public class ThriftClientPool<T extends org.apache.thrift.TServiceClient> {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final ThriftClientFactory clientFactory;

    private final GenericObjectPool<ThriftClient<T>> pool;

    private List<ServiceInfo> services;

    private final PoolConfig poolConfig;

    /**
     * Construct a new pool using default config
     * 
     * @param services
     * @param factory
     */
    public ThriftClientPool(List<ServiceInfo> services, ThriftClientFactory factory) {
        this(services, factory, new PoolConfig());
    }

    /**
     * Construct a new pool using
     * 
     * @param services
     * @param factory
     * @param config
     */
    public ThriftClientPool(List<ServiceInfo> services, ThriftClientFactory factory,
            PoolConfig config) {
        this.services = services;
        this.clientFactory = factory;
        this.poolConfig = config;
        this.poolConfig.setTestOnReturn(true);
        this.pool = new GenericObjectPool<>(new BasePooledObjectFactory<ThriftClient<T>>() {

            @Override
            public ThriftClient<T> create() throws Exception {

                // get from global list first
                List<ServiceInfo> serviceList = ThriftClientPool.this.services;
                ServiceInfo serviceInfo = getRandomService(serviceList);
                TTransport transport = getTransport(serviceInfo);

                try {
                    transport.open();
                } catch (TTransportException e) {
                    logger.info("transport open fail service: host={}, port={}",
                            serviceInfo.getHost(), serviceInfo.getPort());
                    if (poolConfig.isFailover()) {
                        // mark current fail and try next, until none service available
                        serviceList = removeFailService(serviceList, serviceInfo);
                        serviceInfo = getRandomService(serviceList);
                        transport = getTransport(serviceInfo);
                    }
                    throw new ConnectionFailException();
                }

                ThriftClient<T> client = new ThriftClient<>(clientFactory.createClient(transport),
                        pool);

                logger.debug("create new object for pool {}", client);
                return client;
            }

            @Override
            public PooledObject<ThriftClient<T>> wrap(ThriftClient<T> obj) {
                return new DefaultPooledObject<>(obj);
            }

            @Override
            public boolean validateObject(PooledObject<ThriftClient<T>> p) {
                ThriftClient<T> client = p.getObject();
                if (!client.isFinish()) {
                    logger.warn("not return object cause not finish {}", client);
                    return false;
                }
                // reset
                client.setFinish(false);
                return super.validateObject(p);
            }
        }, poolConfig);
    }

    private TTransport getTransport(ServiceInfo serviceInfo) {

        if (serviceInfo == null) {
            throw new NoBackendServiceException();
        }

        TTransport transport = null;
        if (poolConfig.getTimeout() > 0) {
            transport = new TSocket(serviceInfo.getHost(), serviceInfo.getPort(),
                    poolConfig.getTimeout());
        } else {
            transport = new TSocket(serviceInfo.getHost(), serviceInfo.getPort());
        }
        return transport;
    }

    /**
     * get a random service
     * 
     * @param serviceList
     * @return
     */
    private ServiceInfo getRandomService(List<ServiceInfo> serviceList) {
        if (serviceList == null || serviceList.size() == 0) {
            return null;
        }
        return serviceList.get(RandomUtils.nextInt(0, serviceList.size()));
    }

    private List<ServiceInfo> removeFailService(List<ServiceInfo> list, ServiceInfo serviceInfo) {
        logger.info("remove service from current service list: host={}, port={}",
                serviceInfo.getHost(), serviceInfo.getPort());
        return list.stream() //
                .filter(si -> !serviceInfo.equals(si)) //
                .collect(Collectors.toList());
    }

    /**
     * get a client from pool
     * 
     * @return
     * @throws ThriftException
     * @throws NoBackendServiceException if
     *         {@link PoolConfig#setFailover(boolean)} is set and no
     *         service can connect to
     * @throws ConnectionFailException if
     *         {@link PoolConfig#setFailover(boolean)} not set and
     *         connection fail
     */
    public ThriftClient<T> getClient() throws ThriftException {
        try {
            return pool.borrowObject();
        } catch (Exception e) {
            if (e instanceof ThriftException) {
                throw (ThriftException) e;
            }
            throw new ThriftException("Get client from pool failed.", e);
        }
    }

}
