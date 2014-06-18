Buffer
======

Twitter Cards for Buffer

Running
======
You will need Scala 2.10.3 / Java 1.7

On a console

```
git clone https://github.com/ngetha/Buffer.git
cd Buffer
sudo java -Daws.accessKeyId=KEYID -Daws.secretKey=KEY -Dbuffer.destdir=/var/tmp -cp dist/buffer-cards.jar:dist/lib/:/path/to/scala-2.10.3/lib/scala-library.jar buffercards.Main
```

Output
======
You should see

```
12:52:27,751 INFO  CardProcessor - Starting!
12:52:29,389 INFO  CardProcessor - Drainig the Queue at https://sqs.eu-west-1.amazonaws.com/649117426437/buffer-in 10 at a time
12:52:29,406 INFO  CardProcessor - Picking the next batch
[{"pkgName":"co.vine.android","title":"BufferApp","cmd":"create","desc":"Update Your Twitter, FB, Vine","ipadId":"306934135","iphoneId":"306934135"}]
12:52:31,111 INFO  CardProcessor - Casting to JSON
12:52:31,277 INFO  CardProcessor - Map(desc -> Update Your Twitter, FB, Vine, pkgName -> co.vine.android, iphoneId -> 306934135, cmd -> create, ipadId -> 306934135, title -> BufferApp)
12:52:31,393 INFO  CardProcessor - Writing to /var/tmp/bufferapp.html
12:52:31,425 INFO  CardProcessor - File created
12:52:31,436 INFO  CardProcessor - Sending Out JSON -> [{"id":"some-id","cmd":"create-resp","status":"ok","path":"/var/tmp/bufferapp.html"}]
12:52:31,497 INFO  CardProcessor - Done!

```
And the File ```/var/tmp/bufferapp.html``` should contain valid ```TwitterCard``` Meta Tags
