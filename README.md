DynamoDBJournal for Akka Persistence
====================================

A replicated [Akka Persistence](http://doc.akka.io/docs/akka/2.4.0/scala/persistence.html) journal backed by
[Amazon DynamoDB](http://aws.amazon.com/dynamodb/).

**このモジュールには、snapshot-storeプラグインもAkka Persistence Queryプラグインも含まれていないことに注意してください。**

Scala: `2.11.x` or `2.12.1`  Akka: `2.4.14`  Java: `8+`

[![Join the chat at https://gitter.im/akka/akka-persistence-dynamodb](https://badges.gitter.im/akka/akka-persistence-dynamodb.svg)](https://gitter.im/akka/akka-persistence-dynamodb?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)
[![Build Status](https://travis-ci.org/akka/akka-persistence-dynamodb.svg?branch=master)](https://travis-ci.org/akka/akka-persistence-dynamodb)

Installation
------------

このプラグインはMaven Centralリポジトリに以下の名前で公開されています:

~~~
<dependency>
    <groupId>com.typesafe.akka</groupId>
    <artifactId>akka-persistence-dynamodb_2.11</artifactId>
    <version>1.0.0</version>
</dependency>
~~~

or for sbt users:

```sbt
libraryDependencies += "com.typesafe.akka" % "akka-persistence-dynamodb_2.11" % "1.0.0"
```

Scalaバージョン2.12.1以降を使用する場合は、_2.12を_2.11サフィックスに置き換えてください。このプラグインにはJava 8が必要です（Akka自体と同様）。

Configuration
-------------

~~~
akka.persistence.journal.plugin = "my-dynamodb-journal"

my-dynamodb-journal = ${dynamodb-journal} # include the default settings
my-dynamodb-journal {                     # and add some overrides
    journal-table =  <the name of the table to be used>
    journal-name =  <prefix to be used for all keys stored by this plugin>
    aws-access-key-id =  <your key>
    aws-secret-access-key =  <your secret>
    endpoint =  "https://dynamodb.us-east-1.amazonaws.com" # or where your deployment is
}
~~~

エンドポイントURLの詳細については、[DynamoDBのマニュアル](http://docs.aws.amazon.com/general/latest/gr/rande.html#ddb_region)を参照してください。このジャーナルプラグインをあなたのユースケースに微調整したり適応させたりするために使用できるその他の設定は、[reference.conf](https://github.com/akka/akka-persistence-dynamodb/blob/master/src/main/resources/reference.conf)ファイルを参照してください。

これらの設定を使用するには、まずテーブルを作成する必要があります。次のスキーマを使用して、AWSコンソールを使用します。

  * 名前が`par`のString型のハッシュキー
  * 名前が`num`の型Numberのソートキー

Storage Semantics
-----------------

DynamoDBは、このAkka Persistenceプラグインの場合の1つのイベントに対応する、単一のストレージ項目に対する整合性保証のみを提供します。 これは、任意の単一のイベントがジャーナルに書き込まれる（したがって、後のリプレイに表示される）か、そうでないことを意味します。 それにもかかわらず、このプラグインは部分的な再生を避けることができるように、含まれているイベントをマークすることにより、アトミックなマルチイベントバッチをサポートします（下記のストレージフォーマットの説明の`idx`と`cnt`の属性を参照）。 PersistentActorの次の動作を考えてみましょう。

```scala
val events = List(<some events>)
if (atomic) {
  persistAll(events)(handler)
else {
  for (event <- events) persist(event)(handler)
}
```

最初のケースでは、リカバリではすべてのイベントのみが表示されるか、まったく表示されません。 これは、リカバリするシーケンス番号の上限またはリプレイするイベントの数の上限を指定してリカバリが要求された場合も同様です。 不完全なバッチ書き込みを削除する前にイベント数の制限が適用されます。これは、アクターで受け取ったイベントの実際の数が、さらにイベントが使用可能であっても要求された制限よりも低くなる可能性があることを意味します。

後者の場合、各イベントは単独で処理され、正常に永続化されたかどうかによって再生される場合とされない場合があります。

Performance Considerations
--------------------------

このプラグインはAWS Java SDKを使用します。つまり、同時に実行できるリクエスト数は、DynamoDBへの接続数と、AWS HTTPクライアントで使用されるスレッドプール内のスレッド数によって制限されます。 既定の設定は50の接続です。同じEC2領域から使用される展開では、1秒あたり約5000件の要求が許可されます（永続化されたイベントバッチはすべて約1回の要求です）。 1つのActorSystemがこの1秒あたりのイベント数を超えて保持する必要がある場合は、パラメータを調整することができます

~~~
my-dynamodb-journal.aws-client-config.max-connections = <your value here>
~~~

この数を変更すると、同時接続数と使用されるスレッドプールサイズの両方が変更されます。

Compatibility with pre-1.0 versions
-----------------------------------

パフォーマンスと正確性の理由からストレージレイアウトが互換性がないため、古いプラグインで保存されたイベントは1.0以降のバージョンでは使用できません。

Plugin Development
------------------

### Dev Setup

* Run `./scripts/dev-setup.sh` to download and start [DynamoDB-local](http://docs.aws.amazon.com/amazondynamodb/latest/developerguide/Tools.DynamoDBLocal.html).
* Now you are all set for running the test suite from `sbt`.
* In order to stop the DynamoDB process you may execute `./scripts/kill-dynamoDB.sh`.

Please also read the [CONTRIBUTING.md](CONTRIBUTING.md) file.

### DynamoDB table structure discussion

dynamodbでのジャーナル・ストレージの構造は、パフォーマンス・チューニングの反復で進化しました。 これらのレッスンのほとんどは、イベントソースのdynamodbジャーナルの作成で学んだが、ここでも同様に適用する。

##### Naïve structure

ダイナモで最初にジャーナルストレージをモデル化するとき、このような単純な構造を使用するのは当然のようです

```
persistenceId : S : HashKey
sequenceNr    : N : RangeKey
payload       : B
```

これはジャーナルが解決する必要のある操作に非常によく似ています。

```
writeMessage      -> PutItem
deleteMessage     -> DeleteItem
replayMessages    -> Query by persistenceId, conditions and ordered by sequenceNr, ascending
highCounter       -> Query by persistenceId, conditions and ordered by sequenceNr, descending limit 1
```

しかしながら、このレイアウトはスケーラビリティの問題を抱えている。 ハッシュキーを使用してデータストレージノードを特定するため、単一プロセッサのすべての書き込みが同じDynamoDBノードに送られ、テーブルにプロビジョニングされたスループットのレベルに関係なく、スループットが制限され、スロットルが招待されます 熱すぎる。 また、これは、クエリN + 1に対してクエリNで最後に処理されたアイテムを使用する一連のクエリをステップ実行する必要があるため、再生スループットを制限します。

##### Higher throughput structure

以下の略語を使用します。

~~~
P -> PersistentRepr
SH -> SequenceHigh
SL -> SequenceLow
~~~

PersistentReprストレージを次のようにモデル化します。

~~~
par = <journalName>-P-<persistenceId>-<sequenceNr / 100> : S : HashKey
num = <sequenceNr % 100>                                 : N : RangeKey
pay = <payload>                                          : B
idx = <atomic write batch index>                         : N (possibly absent)
cnt = <atomic write batch max index>                     : N (possibly absent)
~~~

高いシーケンス番号

~~~
par = <journalName>-SH-<persistenceId>-<(sequenceNr / 100) % sequenceShards> : S : HashKey
num = 0                                                                      : N : RangeKey
seq = <sequenceNr rounded down to nearest multiple of 100>                   : N
~~~

低シーケンス番号

~~~
par = <journalName>-SL-<persistenceId>-<(sequenceNr / 100) % sequenceShards> : S : HashKey
num = 0                                                                      : N : RangeKey
seq = <sequenceNr, not rounded>                                              : N
~~~

これはコード化するのがやや困難ですが、より高いスループットの可能性を提供します。 1つのアイテムを使用してカウンタを格納するのではなく、上位シーケンスと下位シーケンスを保持するアイテムが断片化されることに注意してください。 単一のアイテムしか使用しなかった場合、最初の構造と同じホットキーの問題が発生します。

アイテムを書き込むときには通常、シーケンス番号の高い記憶域に触れません。ソートキー`0`のアイテムを書き込むときのみ、これが行われます。 これは、最も高いシーケンス番号を読み取るには、最初に100の最高倍数のシーケンス断片を照会し、対応するPエントリのハッシュキーの`Query`を送信して、格納されている最高のソートキー番号を見つける必要があることを意味します。

Credits
-------

Initial development was done by [Scott Clasen](https://github.com/sclasen/akka-persistence-dynamodb). Update to Akka 2.4 and further development up to version 1.0 was kindly sponsored by [Zynga Inc.](https://www.zynga.com/).
