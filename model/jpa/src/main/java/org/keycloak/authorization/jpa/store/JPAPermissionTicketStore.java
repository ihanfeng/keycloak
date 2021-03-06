/*
 * Copyright 2017 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.keycloak.authorization.jpa.store;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javax.persistence.EntityManager;
import javax.persistence.FlushModeType;
import javax.persistence.Query;
import javax.persistence.TypedQuery;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;

import org.keycloak.authorization.AuthorizationProvider;
import org.keycloak.authorization.jpa.entities.PermissionTicketEntity;
import org.keycloak.authorization.model.PermissionTicket;
import org.keycloak.authorization.model.ResourceServer;
import org.keycloak.authorization.store.PermissionTicketStore;
import org.keycloak.models.utils.KeycloakModelUtils;

/**
 * @author <a href="mailto:psilva@redhat.com">Pedro Igor</a>
 */
public class JPAPermissionTicketStore implements PermissionTicketStore {

    private final EntityManager entityManager;
    private final AuthorizationProvider provider;

    public JPAPermissionTicketStore(EntityManager entityManager, AuthorizationProvider provider) {
        this.entityManager = entityManager;
        this.provider = provider;
    }

    @Override
    public PermissionTicket create(String resourceId, String scopeId, String requester, ResourceServer resourceServer) {
        PermissionTicketEntity entity = new PermissionTicketEntity();

        entity.setId(KeycloakModelUtils.generateId());
        entity.setResource(ResourceAdapter.toEntity(entityManager, provider.getStoreFactory().getResourceStore().findById(resourceId, resourceServer.getId())));
        entity.setRequester(requester);
        entity.setCreatedTimestamp(System.currentTimeMillis());

        if (scopeId != null) {
            entity.setScope(ScopeAdapter.toEntity(entityManager, provider.getStoreFactory().getScopeStore().findById(scopeId, resourceServer.getId())));
        }

        entity.setOwner(entity.getResource().getOwner());
        entity.setResourceServer(ResourceServerAdapter.toEntity(entityManager, resourceServer));

        this.entityManager.persist(entity);
        this.entityManager.flush();
        PermissionTicket model = new PermissionTicketAdapter(entity, entityManager, provider.getStoreFactory());
        return model;
    }

    @Override
    public void delete(String id) {
        PermissionTicketEntity policy = entityManager.find(PermissionTicketEntity.class, id);
        if (policy != null) {
            this.entityManager.remove(policy);
        }
    }


    @Override
    public PermissionTicket findById(String id, String resourceServerId) {
        if (id == null) {
            return null;
        }

        PermissionTicketEntity entity = entityManager.find(PermissionTicketEntity.class, id);
        if (entity == null) return null;

        return new PermissionTicketAdapter(entity, entityManager, provider.getStoreFactory());
    }

    @Override
    public List<PermissionTicket> findByResourceServer(final String resourceServerId) {
        TypedQuery<String> query = entityManager.createNamedQuery("findPolicyIdByServerId", String.class);

        query.setParameter("serverId", resourceServerId);

        List<String> result = query.getResultList();
        List<PermissionTicket> list = new LinkedList<>();
        for (String id : result) {
            list.add(provider.getStoreFactory().getPermissionTicketStore().findById(id, resourceServerId));
        }
        return list;
    }

    @Override
    public List<PermissionTicket> findByResource(final String resourceId, String resourceServerId) {
        TypedQuery<String> query = entityManager.createNamedQuery("findPermissionIdByResource", String.class);

        query.setFlushMode(FlushModeType.COMMIT);
        query.setParameter("resourceId", resourceId);
        query.setParameter("serverId", resourceServerId);

        List<String> result = query.getResultList();
        List<PermissionTicket> list = new LinkedList<>();
        for (String id : result) {
            list.add(provider.getStoreFactory().getPermissionTicketStore().findById(id, resourceServerId));
        }
        return list;
    }

    @Override
    public List<PermissionTicket> findByScope(String scopeId, String resourceServerId) {
        if (scopeId==null) {
            return Collections.emptyList();
        }

        // Use separate subquery to handle DB2 and MSSSQL
        TypedQuery<String> query = entityManager.createNamedQuery("findPermissionIdByScope", String.class);

        query.setFlushMode(FlushModeType.COMMIT);
        query.setParameter("scopeId", scopeId);
        query.setParameter("serverId", resourceServerId);

        List<String> result = query.getResultList();
        List<PermissionTicket> list = new LinkedList<>();
        for (String id : result) {
            list.add(provider.getStoreFactory().getPermissionTicketStore().findById(id, resourceServerId));
        }
        return list;
    }

    @Override
    public List<PermissionTicket> find(Map<String, String> attributes, String resourceServerId, int firstResult, int maxResult) {
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<PermissionTicketEntity> querybuilder = builder.createQuery(PermissionTicketEntity.class);
        Root<PermissionTicketEntity> root = querybuilder.from(PermissionTicketEntity.class);

        querybuilder.select(root.get("id"));

        List<Predicate> predicates = new ArrayList();

        if (resourceServerId != null) {
            predicates.add(builder.equal(root.get("resourceServer").get("id"), resourceServerId));
        }

        attributes.forEach((name, value) -> {
            if (PermissionTicket.ID.equals(name)) {
                predicates.add(root.get(name).in(value));
            } else if (PermissionTicket.SCOPE.equals(name)) {
                predicates.add(root.join("scope").get("id").in(value));
            } else if (PermissionTicket.SCOPE_IS_NULL.equals(name)) {
                if (Boolean.valueOf(value)) {
                    predicates.add(builder.isNull(root.get("scope")));
                } else {
                    predicates.add(builder.isNotNull(root.get("scope")));
                }
            } else if (PermissionTicket.RESOURCE.equals(name)) {
                predicates.add(root.join("resource").get("id").in(value));
            } else if (PermissionTicket.OWNER.equals(name)) {
                predicates.add(builder.equal(root.get("owner"), value));
            } else if (PermissionTicket.REQUESTER.equals(name)) {
                predicates.add(builder.equal(root.get("requester"), value));
            } else if (PermissionTicket.GRANTED.equals(name)) {
                if (Boolean.valueOf(value)) {
                    predicates.add(builder.isNotNull(root.get("grantedTimestamp")));
                } else {
                    predicates.add(builder.isNull(root.get("grantedTimestamp")));
                }
            } else if (PermissionTicket.REQUESTER_IS_NULL.equals(name)) {
                predicates.add(builder.isNull(root.get("requester")));
            } else {
                throw new RuntimeException("Unsupported filter [" + name + "]");
            }
        });

        querybuilder.where(predicates.toArray(new Predicate[predicates.size()])).orderBy(builder.asc(root.get("resource").get("id")));

        Query query = entityManager.createQuery(querybuilder);

        if (firstResult != -1) {
            query.setFirstResult(firstResult);
        }
        if (maxResult != -1) {
            query.setMaxResults(maxResult);
        }

        List<String> result = query.getResultList();
        List<PermissionTicket> list = new LinkedList<>();
        PermissionTicketStore ticket = provider.getStoreFactory().getPermissionTicketStore();

        for (String id : result) {
            list.add(ticket.findById(id, resourceServerId));
        }

        return list;
    }

    @Override
    public List<PermissionTicket> findByOwner(String owner, String resourceServerId) {
        TypedQuery<String> query = entityManager.createNamedQuery("findPolicyIdByType", String.class);

        query.setFlushMode(FlushModeType.COMMIT);
        query.setParameter("serverId", resourceServerId);
        query.setParameter("owner", owner);

        List<String> result = query.getResultList();
        List<PermissionTicket> list = new LinkedList<>();
        for (String id : result) {
            list.add(provider.getStoreFactory().getPermissionTicketStore().findById(id, resourceServerId));
        }
        return list;
    }
}
