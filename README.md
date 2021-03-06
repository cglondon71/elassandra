# Elassandra [![Build Status](https://travis-ci.org/strapdata/elassandra.svg)](https://travis-ci.org/strapdata/elassandra) [![Doc Status](https://readthedocs.org/projects/elassandra/badge/?version=latest)](http://doc.elassandra.io)

![Elassandra Logo](elassandra-logo.png)

## [http://www.elassandra.io/](http://www.elassandra.io/)

Elassandra is a fork of [Elasticsearch](https://github.com/elastic/elasticsearch) modified to run as a plugin for [Apache Cassandra](http://cassandra.apache.org) in a scalable and resilient peer-to-peer architecture. Elasticsearch code is embedded in Cassanda nodes providing advanced search features on Cassandra tables and Cassandra serves as an Elasticsearch data and configuration store.

![Elassandra architecture](/docs/elassandra/source/images/elassandra1.jpg)

Elassandra supports Cassandra vnodes and scales horizontally by adding more nodes.

Project documentation is available at [doc.elassandra.io](http://doc.elassandra.io).

## Benefits of Elassandra

For Cassandra users, elassandra provides Elasticsearch features :
* Cassandra updates are indexed in Elasticsearch.
* Full-text and spatial search on your Cassandra data.
* Real-time aggregation (does not require Spark or Hadoop to GROUP BY)
* Provide search on multiple keyspaces and tables in one query.
* Provide automatic schema creation and support nested documents using [User Defined Types](https://docs.datastax.com/en/cql/3.1/cql/cql_using/cqlUseUDT.html).
* Provide read/write JSON REST access to Cassandra data.
* Numerous Elasticsearch plugins and products like [Kibana](https://www.elastic.co/guide/en/kibana/current/introduction.html).

For Elasticsearch users, elassandra provides useful features :
* Elassandra is masterless. Cluster state is managed through [cassandra lightweight transactions](http://www.datastax.com/dev/blog/lightweight-transactions-in-cassandra-2-0).
* Elassandra is a sharded multi-master database, where Elasticsearch is sharded master-slave. Thus, Elassandra has no Single Point Of Write, helping to achieve high availability.
* Elassandra inherits Cassandra data repair mechanisms (hinted handoff, read repair and nodetool repair) providing support for **cross datacenter replication**.
* When adding a node to an Elassandra cluster, only data pulled from existing nodes are re-indexed in Elasticsearch.
* Cassandra could be your unique datastore for indexed and non-indexed data. It's easier to manage and secure. Source documents are now stored in Cassandra, reducing disk space if you need a NoSQL database and Elasticsearch.
* Write operations are not restricted to one primary shard, but distributed across all Cassandra nodes in a virtual datacenter. The number of shards does not limit your write throughput. Adding elassandra nodes increases both read and write throughput.
* Elasticsearch indices can be replicated among many Cassandra datacenters, allowing write to the closest datacenter and search globally.
* The [cassandra driver](http://www.planetcassandra.org/client-drivers-tools/) is Datacenter and Token aware, providing automatic load-balancing and failover.

## Quick start

#### Elasticsearch 6.x changes

* Elasticsearch now supports only one document type per index backed by one Cassandra table. Unless you specify an elasticsearch type name in your mapping, data is stored in a cassandra table named **"_doc"**. If you want to search many cassandra tables, you now need to create and search many indices.
* Elasticsearch 6.x manages shard consistency through several metadata fields (_primary_term, _seq_no, _version) that are not used in elassandra because replication is fully managed by cassandra.

#### Requirements

Ensure Java 8 is installed and `JAVA_HOME` points to the correct location.

#### Installation

* [Download](https://github.com/strapdata/elassandra/releases) and extract the distribution tarball
* Define the CASSANDRA_HOME environment variable : `export CASSANDRA_HOME=<extracted_directory>`
* Run `bin/cassandra -e`
* Run `bin/nodetool status`
* Run `curl -XGET localhost:9200/_cluster/state`

#### Example

Try indexing a document on a non-existing index:
```bash
curl -XPUT 'http://localhost:9200/twitter/_doc/1?pretty' -H 'Content-Type: application/json' -d '
{
    "user": "Poulpy",
    "post_date": "2017/10/4 13:12:00",
    "message": "Elassandra adds dynamic mapping to Cassandra"
}'
```

Then look-up in Cassandra:
```bash
bin/cqlsh -c "SELECT * from twitter.\"_doc\""
```
Behind the scenes, Elassandra has created a new Keyspace `twitter` and table `_doc`.

Now, insert a row with CQL :
```CQL
INSERT INTO twitter.doc ("_id", user, post_date, message)
VALUES ( '2', ['Jimmy'], [dateof(now())], ['New data is indexed automatically']);
```

Then search for it with the Elasticsearch API:
```bash
curl "localhost:9200/twitter/_search?q=user:Jimmy&pretty"
```

And here is a sample response :
```JSON
{
  "took" : 1,
  "timed_out" : false,
  "_shards" : {
    "total" : 1,
    "successful" : 1,
    "failed" : 0
  },
  "hits" : {
    "total" : 1,
    "max_score" : 0.9808292,
    "hits" : [
      {
        "_index" : "twitter",
        "_type" : "doc",
        "_id" : "2",
        "_score" : 0.9808292,
        "_source" : {
          "post_date" : "2017/10/04 13:20:00",
          "message" : "New data is indexed automatically",
          "user" : "Jimmy"
        }
      }
    ]
  }
}

```

## Support

 * Commercial support is available through [Strapdata](http://www.strapdata.com/).
 * Community support available via [elassandra google groups](https://groups.google.com/forum/#!forum/elassandra).
 * Post feature requests and bugs on https://github.com/strapdata/elassandra/issues

## License

```
This software is licensed under the Apache License, version 2 ("ALv2"), quoted below.

Copyright 2015-2018, Strapdata (contact@strapdata.com).

Licensed under the Apache License, Version 2.0 (the "License"); you may not
use this file except in compliance with the License. You may obtain a copy of
the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
License for the specific language governing permissions and limitations under
the License.
```

## Acknowledgments

* Elasticsearch and Kibana are trademarks of Elasticsearch BV, registered in the U.S. and in other countries.
* Apache Cassandra, Apache Lucene, Apache, Lucene and Cassandra are trademarks of the Apache Software Foundation.
* Elassandra is a trademark of Strapdata SAS.
