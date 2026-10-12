<?php
declare(strict_types=1);
if(PHP_SAPI!=='cli') {http_response_code(404);exit;}
require __DIR__.'/app.php';
$command=$argv[1]??'';$directory=$argv[2]??'';
if(!$directory || $directory[0]!=='/')throw new RuntimeException('An explicit absolute private directory is required');
if($command==='init')echo json_encode(Redwood::initialize($directory),JSON_PRETTY_PRINT)."\n";
elseif($command==='invite'){$role=$argv[3]??'member';if(!in_array($role,['owner','member'],true))throw new RuntimeException('Invalid role');echo json_encode(['invite'=>(new Redwood($directory))->invite($role),'expires_in_days'=>7])."\n";}
elseif($command==='bootstrap-invites'){$app=new Redwood($directory);$codes=$app->transaction(function()use($app){foreach(['users','groups','versions','blobs'] as $table)if((int)$app->one('SELECT COUNT(*) n FROM '.$table)['n']!==0)throw new RuntimeException('Bootstrap recovery requires an empty library');$app->execute('UPDATE invites SET used=1 WHERE used=0');return ['diego_invite'=>$app->invite('owner'),'iris_invite'=>$app->invite('member'),'expires_in_days'=>7];});echo json_encode($codes)."\n";}
elseif($command==='status'){$app=new Redwood($directory);foreach(['users','groups','versions','blobs'] as $table)echo $table.': '.$app->one('SELECT COUNT(*) n FROM '.$table)['n']."\n";echo 'integrity: '.$app->db->query('PRAGMA quick_check')->fetchColumn()."\n";}
elseif($command==='persistent-sessions')echo json_encode((new Redwood($directory))->makeSessionsPersistent())."\n";
elseif($command==='session-status'){$app=new Redwood($directory);echo json_encode(['policy'=>$app->one("SELECT value FROM meta WHERE key='device_session_policy'")['value']??'not_migrated','persistent_sessions'=>(int)$app->one('SELECT COUNT(*) n FROM tokens WHERE expires=0')['n'],'expiring_active_sessions'=>(int)$app->one('SELECT COUNT(*) n FROM tokens WHERE expires>?',[time()])['n']])."\n";}
elseif($command==='backup'){$app=new Redwood($directory);$folder=$directory.'/backups';if(!is_dir($folder))mkdir($folder,0700);$path=$folder.'/library-'.gmdate('Ymd-His').'-'.bin2hex(random_bytes(4)).'.sqlite';$app->db->exec('VACUUM INTO '.$app->db->quote($path));chmod($path,0600);$check=new PDO('sqlite:'.$path);if($check->query('PRAGMA integrity_check')->fetchColumn()!=='ok')throw new RuntimeException('Backup integrity check failed');echo basename($path)."\n";}
else throw new RuntimeException('Usage: cli.php init|invite|status|backup|persistent-sessions|session-status /absolute/private/directory [owner|member]');
