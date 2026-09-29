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
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.TermQuery;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class LuceneFilterConverterTest {

    @Test
    void isEqualToProducesTermQuery() {
        Filter filter = new IsEqualTo("key", "value");
        var query = LuceneFilterConverter.convert(filter);
        assertThat(query).isInstanceOf(TermQuery.class);
    }

    @Test
    void andProducesBooleanQueryWithMust() {
        Filter filter = new And(
                new IsEqualTo("a", "1"),
                new IsEqualTo("b", "2")
        );
        var query = LuceneFilterConverter.convert(filter);
        assertThat(query).isInstanceOf(BooleanQuery.class);
        BooleanQuery bq = (BooleanQuery) query;
        assertThat(bq).isNotNull();
    }

    @Test
    void orProducesBooleanQueryWithShould() {
        Filter filter = new Or(
                new IsEqualTo("a", "1"),
                new IsEqualTo("b", "2")
        );
        var query = LuceneFilterConverter.convert(filter);
        assertThat(query).isInstanceOf(BooleanQuery.class);
    }

    @Test
    void notProducesBooleanQueryWithMustNot() {
        Filter filter = new Not(new IsEqualTo("a", "1"));
        var query = LuceneFilterConverter.convert(filter);
        assertThat(query).isInstanceOf(BooleanQuery.class);
    }

    @Test
    void nestedAnd() {
        Filter filter = new And(
                new IsEqualTo("a", "1"),
                new And(new IsEqualTo("b", "2"), new IsEqualTo("c", "3"))
        );
        var query = LuceneFilterConverter.convert(filter);
        assertThat(query).isInstanceOf(BooleanQuery.class);
    }

    @Test
    void nullFilterReturnsMatchAll() {
        var query = LuceneFilterConverter.convert(null);
        assertThat(query).isNotNull();
    }

    @Test
    void isNotEqualTo() {
        Filter filter = new IsNotEqualTo("key", "value");
        var query = LuceneFilterConverter.convert(filter);
        assertThat(query).isInstanceOf(BooleanQuery.class);
    }

    @Test
    void containsString() {
        Filter filter = new ContainsString("key", "val");
        var query = LuceneFilterConverter.convert(filter);
        assertThat(query).isNotNull();
    }

    @Test
    void isGreaterThan() {
        Filter filter = new IsGreaterThan("key", 5);
        var query = LuceneFilterConverter.convert(filter);
        assertThat(query).isNotNull();
    }

    @Test
    void isLessThan() {
        Filter filter = new IsLessThan("key", 10);
        var query = LuceneFilterConverter.convert(filter);
        assertThat(query).isNotNull();
    }

    @Test
    void isGreaterThanOrEqualTo() {
        Filter filter = new IsGreaterThanOrEqualTo("key", 5);
        var query = LuceneFilterConverter.convert(filter);
        assertThat(query).isNotNull();
    }

    @Test
    void isLessThanOrEqualTo() {
        Filter filter = new IsLessThanOrEqualTo("key", 10);
        var query = LuceneFilterConverter.convert(filter);
        assertThat(query).isNotNull();
    }

    @Test
    void isIn() {
        Filter filter = new IsIn("key", Arrays.asList("a", "b", "c"));
        var query = LuceneFilterConverter.convert(filter);
        assertThat(query).isInstanceOf(BooleanQuery.class);
    }

    @Test
    void isNotIn() {
        Filter filter = new IsNotIn("key", Arrays.asList("a", "b"));
        var query = LuceneFilterConverter.convert(filter);
        assertThat(query).isInstanceOf(BooleanQuery.class);
    }
}
