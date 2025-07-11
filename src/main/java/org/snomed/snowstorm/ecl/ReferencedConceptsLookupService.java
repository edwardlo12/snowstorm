package org.snomed.snowstorm.ecl;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TermsLookup;
import io.kaicode.elasticvc.api.BranchCriteria;
import org.snomed.snowstorm.core.data.domain.ReferencedConceptsLookup;
import org.snomed.snowstorm.core.data.repositories.ReferencedConceptsLookupRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.client.elc.NativeQueryBuilder;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.FetchSourceFilter;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.bool;
import static io.kaicode.elasticvc.helper.QueryHelper.termsQuery;

@Service
public class ReferencedConceptsLookupService {
    private final ElasticsearchOperations elasticsearchOperations;
    private final ReferencedConceptsLookupRepository conceptsLookupRepository;
    @Autowired
    public ReferencedConceptsLookupService(ElasticsearchOperations elasticsearchOperations, ReferencedConceptsLookupRepository conceptsLookupRepository) {
        this.elasticsearchOperations = elasticsearchOperations;
        this.conceptsLookupRepository = conceptsLookupRepository;
    }
    public List<ReferencedConceptsLookup> getConceptsLookups(BranchCriteria branchCriteria, boolean includeConceptIds) {
        return getConceptsLookups(branchCriteria, null, includeConceptIds);
    }

    public List<ReferencedConceptsLookup> getConceptsLookups(BranchCriteria branchCriteria) {
        return getConceptsLookups(branchCriteria, null, true);
    }

    public List<ReferencedConceptsLookup> getConceptsLookups(BranchCriteria branchCriteria, Collection<Long> refsetIds) {
        return getConceptsLookups(branchCriteria, refsetIds, true);
    }

    public List<ReferencedConceptsLookup> getConceptsLookups(BranchCriteria branchCriteria, Collection<Long> refsetIds, boolean includeConceptIds) {
        NativeQueryBuilder queryBuilder;
        if (refsetIds == null || refsetIds.isEmpty()) {
            queryBuilder = new NativeQueryBuilder().withQuery(branchCriteria.getEntityBranchCriteria(ReferencedConceptsLookup.class));
        } else {
            queryBuilder = new NativeQueryBuilder().withQuery(bool(b -> b
                    .must(branchCriteria.getEntityBranchCriteria(ReferencedConceptsLookup.class))
                    .must(termsQuery(ReferencedConceptsLookup.Fields.REFSETID, refsetIds)
                    )));
        }
        // Should exclude conceptIds from the response as it can be a large list
        // Retrieve only for testing or update lookups with conceptIds
        if (!includeConceptIds) {
            queryBuilder.withSourceFilter(new FetchSourceFilter(null, new String[]{ReferencedConceptsLookup.Fields.CONCEPT_IDS}));
        }
        queryBuilder.withPageable(Pageable.ofSize(1000));
        SearchHits<ReferencedConceptsLookup> refsetConceptsLookupSearchHits = elasticsearchOperations.search(queryBuilder.build(), ReferencedConceptsLookup.class);
        List<ReferencedConceptsLookup> results = new ArrayList<>();
        refsetConceptsLookupSearchHits.forEach(hit -> results.add(hit.getContent()));
        return results;
    }

    public Query constructQueryWithLookups(Collection<ReferencedConceptsLookup> lookups, String fieldName) {
        List<Query> includeFilters = new ArrayList<>();
        List<Query> excludeFilters = new ArrayList<>();

        lookups.forEach(lookup -> {
            if (lookup.getTotal() > 0) {
                // Build the terms lookup
                TermsLookup termsLookup = new TermsLookup.Builder()
                        .index(elasticsearchOperations.getIndexCoordinatesFor(ReferencedConceptsLookup.class).getIndexName())
                        .id(lookup.getId())
                        .path(ReferencedConceptsLookup.Fields.CONCEPT_IDS)
                        .build();

                // Build the query
                Query termsLookupQuery = Query.of(q -> q.terms(t -> t
                        .field(fieldName)
                        .terms(termsQueryField -> termsQueryField.lookup(termsLookup))));

                if (ReferencedConceptsLookup.Type.INCLUDE == lookup.getType()) {
                    includeFilters.add(termsLookupQuery);
                } else {
                    excludeFilters.add(termsLookupQuery);
                }
            }
        });

        Query combinedQuery = Query.of(q ->
                q.bool(b -> {
                    if (!includeFilters.isEmpty()) {
                        b.should(includeFilters);
                    }
                    if (!excludeFilters.isEmpty()) {
                        b.mustNot(excludeFilters);
                    }
                    return b;
                })
        );
        return NativeQuery.builder().withFilter(combinedQuery).build().getFilter();
    }

    public void deleteAll() {
        conceptsLookupRepository.deleteAll();
    }

    public void saveAll(List<ReferencedConceptsLookup> lookups) {
        conceptsLookupRepository.saveAll(lookups);
    }
}
