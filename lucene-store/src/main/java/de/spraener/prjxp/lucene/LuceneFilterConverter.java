package de.spraener.prjxp.lucene;

import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.comparison.ContainsString;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import dev.langchain4j.store.embedding.filter.comparison.IsGreaterThan;
import dev.langchain4j.store.embedding.filter.comparison.IsGreaterThanOrEqualTo;
import dev.langchain4j.store.embedding.filter.comparison.IsIn;
import dev.langchain4j.store.embedding.filter.comparison.IsLessThan;
import dev.langchain4j.store.embedding.filter.comparison.IsLessThanOrEqualTo;
import dev.langchain4j.store.embedding.filter.comparison.IsNotEqualTo;
import dev.langchain4j.store.embedding.filter.comparison.IsNotIn;
import dev.langchain4j.store.embedding.filter.logical.And;
import dev.langchain4j.store.embedding.filter.logical.Not;
import dev.langchain4j.store.embedding.filter.logical.Or;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.WildcardQuery;
import org.apache.lucene.util.BytesRef;

import java.util.Collection;

public class LuceneFilterConverter {

    private LuceneFilterConverter() {
    }

    public static Query convert(Filter filter) {
        if (filter == null) {
            return new MatchAllDocsQuery();
        }

        if (filter instanceof And) {
            And and = (And) filter;
            return new BooleanQuery.Builder()
                    .add(convert(and.left()), BooleanClause.Occur.MUST)
                    .add(convert(and.right()), BooleanClause.Occur.MUST)
                    .build();
        }

        if (filter instanceof Or) {
            Or or = (Or) filter;
            return new BooleanQuery.Builder()
                    .add(convert(or.left()), BooleanClause.Occur.SHOULD)
                    .add(convert(or.right()), BooleanClause.Occur.SHOULD)
                    .build();
        }

        if (filter instanceof Not) {
            Not not = (Not) filter;
            return new BooleanQuery.Builder()
                    .add(convert(not.expression()), BooleanClause.Occur.MUST_NOT)
                    .build();
        }

        if (filter instanceof IsEqualTo) {
            IsEqualTo eq = (IsEqualTo) filter;
            return new TermQuery(new Term(eq.key(), eq.comparisonValue().toString()));
        }

        if (filter instanceof IsNotEqualTo) {
            IsNotEqualTo neq = (IsNotEqualTo) filter;
            return new BooleanQuery.Builder()
                    .add(new TermQuery(new Term(neq.key(), neq.comparisonValue().toString())), BooleanClause.Occur.MUST_NOT)
                    .build();
        }

        if (filter instanceof ContainsString) {
            ContainsString c = (ContainsString) filter;
            return new WildcardQuery(new Term(c.key(), "*" + c.comparisonValue() + "*"));
        }

        if (filter instanceof IsGreaterThan) {
            IsGreaterThan gt = (IsGreaterThan) filter;
            return new org.apache.lucene.search.TermRangeQuery(
                    gt.key(), new BytesRef(gt.comparisonValue().toString()), null, false, true);
        }

        if (filter instanceof IsLessThan) {
            IsLessThan lt = (IsLessThan) filter;
            return new org.apache.lucene.search.TermRangeQuery(
                    lt.key(), null, new BytesRef(lt.comparisonValue().toString()), true, false);
        }

        if (filter instanceof IsGreaterThanOrEqualTo) {
            IsGreaterThanOrEqualTo gte = (IsGreaterThanOrEqualTo) filter;
            return new org.apache.lucene.search.TermRangeQuery(
                    gte.key(), new BytesRef(gte.comparisonValue().toString()), null, true, true);
        }

        if (filter instanceof IsLessThanOrEqualTo) {
            IsLessThanOrEqualTo lte = (IsLessThanOrEqualTo) filter;
            return new org.apache.lucene.search.TermRangeQuery(
                    lte.key(), null, new BytesRef(lte.comparisonValue().toString()), true, true);
        }

        if (filter instanceof IsIn) {
            IsIn in = (IsIn) filter;
            BooleanQuery.Builder builder = new BooleanQuery.Builder();
            for (Object value : in.comparisonValues()) {
                builder.add(new TermQuery(new Term(in.key(), value.toString())), BooleanClause.Occur.SHOULD);
            }
            return builder.build();
        }

        if (filter instanceof IsNotIn) {
            IsNotIn notIn = (IsNotIn) filter;
            BooleanQuery.Builder builder = new BooleanQuery.Builder();
            for (Object value : notIn.comparisonValues()) {
                builder.add(new TermQuery(new Term(notIn.key(), value.toString())), BooleanClause.Occur.MUST_NOT);
            }
            return builder.build();
        }

        throw new IllegalArgumentException("Unsupported filter type: " + filter.getClass().getName());
    }
}
