<?php
/** CLI-only bounded live smoke test. Never deploy beneath a document root. */
declare(strict_types=1);
if(PHP_SAPI!=='cli'){http_response_code(404);exit;}
require __DIR__.'/app.php';
$app=new Redwood(__DIR__);$action=$argv[1]??'';
if($action==='start'){
    $hash=Redwood::hash(trim(stream_get_contents(STDIN,128)));
    // Only the initial empty library is eligible for this live photo probe.
    foreach(['users','groups','versions','blobs'] as $table)if((int)$app->one('SELECT COUNT(*) n FROM '.$table)['n']!==0)throw new RuntimeException('Live smoke test requires an empty unused library');
    $id='qa_'.bin2hex(random_bytes(16));$password=bin2hex(random_bytes(24));
    $file=__DIR__.'/probe-'.$id.'.json';$out=fopen($file,'x');chmod($file,0600);fwrite($out,json_encode(['id'=>$id,'photo'=>$hash]));fclose($out);
    $app->execute('INSERT INTO users VALUES (?,?,?,?)',[$id,$id,password_hash($password,PASSWORD_ARGON2ID),'member']);
    echo json_encode(['id'=>$id,'username'=>$id,'password'=>$password]);
}elseif($action==='stop'){
    $id=$argv[2]??'';if(!preg_match('/^qa_[a-f0-9]{32}$/D',$id))throw new RuntimeException('Invalid probe identity');
    $file=__DIR__.'/probe-'.$id.'.json';$record=json_decode(file_get_contents($file),true,8,JSON_THROW_ON_ERROR);
    if($record['id']!==$id)throw new RuntimeException('Probe receipt mismatch');$hash=Redwood::hash($record['photo']);
    $app->transaction(function()use($app,$id,$hash){
        $user=$app->one('SELECT * FROM users WHERE id=?',[$id]);
        if(!$user||$user['username']!==$id||$user['role']!=='member')throw new RuntimeException('Probe account changed; leave it for inspection');
        if($app->one('SELECT revision FROM versions WHERE author=? OR instr(photos,?)>0 LIMIT 1',[$id,$hash]))throw new RuntimeException('Unexpected grouping refers to the probe; nothing removed');
        $image=__DIR__.'/blobs/'.$hash;
        if(is_file($image)&&!hash_equals(hash_file('sha256',$image),$hash))throw new RuntimeException('Probe pixels changed; nothing removed');
        $app->execute('DELETE FROM tokens WHERE user_id=?',[$id]);$app->execute('DELETE FROM users WHERE id=?',[$id]);
        $app->execute('DELETE FROM blobs WHERE hash=?',[$hash]);if(is_file($image)&&!unlink($image))throw new RuntimeException('Cannot remove verified test pixels');
    });unlink($file);echo '{"probe_account_removed":true,"invented_photo_removed":true}';
}else throw new RuntimeException('Usage: php probe.php start (photo hash on stdin) | stop qa_identity');
