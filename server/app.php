<?php
declare(strict_types=1);

/** Private, invite-only, single-library sync. No cookies or public photo URLs. */
final class RedwoodError extends RuntimeException {
    public function __construct(public int $status, string $message) { parent::__construct($message); }
}
final class Redwood {
    public PDO $db;
    public const PHOTO_LIMIT = 8388608;
    public const DOCUMENT_LIMIT = 1048576;
    public function __construct(public string $directory) {
        if (!is_dir($directory) || !is_file($directory.'/library.sqlite')) throw new RedwoodError(503, 'Library not initialized');
        $this->db = new PDO('sqlite:'.$directory.'/library.sqlite', null, null, [PDO::ATTR_ERRMODE=>PDO::ERRMODE_EXCEPTION]);
        $this->db->exec('PRAGMA busy_timeout=10000; PRAGMA foreign_keys=ON;');
    }
    public static function initialize(string $directory): array {
        if (file_exists($directory.'/library.sqlite')) throw new RuntimeException('Already initialized; existing data was not changed');
        if (!is_dir($directory) && !mkdir($directory,0700,true)) throw new RuntimeException('Cannot create private storage');
        chmod($directory,0700); mkdir($directory.'/blobs',0700);
        $db=new PDO('sqlite:'.$directory.'/library.sqlite'); $db->setAttribute(PDO::ATTR_ERRMODE,PDO::ERRMODE_EXCEPTION);
        $db->exec('PRAGMA journal_mode=WAL;
          CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
          CREATE TABLE users (id TEXT PRIMARY KEY, username TEXT UNIQUE NOT NULL, password TEXT NOT NULL, role TEXT NOT NULL);
          CREATE TABLE invites (hash TEXT PRIMARY KEY, role TEXT NOT NULL, expires INTEGER NOT NULL, used INTEGER NOT NULL DEFAULT 0);
          CREATE TABLE tokens (hash TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id), expires INTEGER NOT NULL);
          CREATE TABLE rate (key TEXT PRIMARY KEY, count INTEGER NOT NULL, expires INTEGER NOT NULL);
          CREATE TABLE blobs (hash TEXT PRIMARY KEY, bytes INTEGER NOT NULL);
          CREATE TABLE versions (revision INTEGER PRIMARY KEY AUTOINCREMENT, group_id TEXT NOT NULL, fingerprint TEXT NOT NULL, body TEXT NOT NULL, photos TEXT NOT NULL, author TEXT NOT NULL REFERENCES users(id), modified INTEGER NOT NULL);
          CREATE INDEX version_group ON versions(group_id,revision);
          CREATE TABLE groups (id TEXT PRIMARY KEY, revision INTEGER NOT NULL REFERENCES versions(revision));');
        chmod($directory.'/library.sqlite',0600);
        $db->prepare('INSERT INTO meta VALUES (?,?)')->execute(['library',bin2hex(random_bytes(16))]);
        $db=null; $app=new self($directory);
        return ['diego_invite'=>$app->invite('owner'),'iris_invite'=>$app->invite('member'),'expires_in_days'=>7];
    }
    public function one(string $sql,array $args=[]): ?array { $s=$this->db->prepare($sql);$s->execute($args);return $s->fetch(PDO::FETCH_ASSOC)?:null; }
    public function execute(string $sql,array $args=[]): void { $this->db->prepare($sql)->execute($args); }
    public function transaction(callable $f): mixed {
        $this->db->exec('BEGIN IMMEDIATE');
        try {$result=$f();$this->db->exec('COMMIT');return $result;}catch(Throwable $e){$this->db->exec('ROLLBACK');throw $e;}
    }
    public function invite(string $role='member'): string {
        $code=bin2hex(random_bytes(24));
        $this->execute('INSERT INTO invites(hash,role,expires) VALUES (?,?,?)',[hash('sha256',$code),$role,time()+7*86400]);return $code;
    }
    public function rate(string $key,int $limit,int $seconds): void {
        $key=hash('sha256',$key);$now=time();
        $this->transaction(function() use($key,$limit,$seconds,$now) {
            $this->execute('DELETE FROM rate WHERE expires<?',[$now]);
            $row=$this->one('SELECT * FROM rate WHERE key=?',[$key]);
            if ($row && (int)$row['count'] >= $limit) throw new RedwoodError(429,'Too many attempts; try again later');
            $this->execute('INSERT INTO rate VALUES (?,1,?) ON CONFLICT(key) DO UPDATE SET count=count+1',[$key,$now+$seconds]);
        });
    }
    public function authenticate(string $token): array {
        if (!preg_match('/^[a-f0-9]{64}$/D',$token)) throw new RedwoodError(401,'Sign in to sync');
        $u=$this->one('SELECT u.* FROM tokens t JOIN users u ON u.id=t.user_id WHERE t.hash=? AND t.expires>?',[hash('sha256',$token),time()]);
        if (!$u) throw new RedwoodError(401,'Your sign-in expired. Offline data is safe.');return $u;
    }
    public function session(array $user): array {
        $token=bin2hex(random_bytes(32));$expires=time()+90*86400;
        $this->execute('DELETE FROM tokens WHERE expires<?',[time()]);
        $this->execute('INSERT INTO tokens VALUES (?,?,?)',[hash('sha256',$token),$user['id'],$expires]);
        return $this->identity($user)+['token'=>$token,'expires'=>$expires];
    }
    public function identity(array $user): array {return ['library'=>$this->one("SELECT value FROM meta WHERE key='library'")['value'],'username'=>$user['username'],'role'=>$user['role']];}
    private static function password(string $password): void {if(strlen($password)<12||strlen($password)>128)throw new RedwoodError(400,'Use a password of 12–128 characters');}
    public function account(string $action,array $input,string $ip): array {
        $username=strtolower(trim((string)($input['username']??'')));$password=(string)($input['password']??'');
        $this->rate('account-ip:'.$ip,40,900);$this->rate('account-name:'.$username,15,900);
        if(!preg_match('/^[a-z0-9][a-z0-9_.-]{2,39}$/D',$username))throw new RedwoodError(400,'Use a username of 3–40 letters, numbers, dots or underscores');
        if($action==='signup') {
            self::password($password);$code=(string)($input['invite']??'');
            if(!preg_match('/^[a-f0-9]{48}$/D',$code))throw new RedwoodError(403,'An unused invitation is required');
            $hash=password_hash($password,PASSWORD_ARGON2ID);
            $user=$this->transaction(function()use($username,$hash,$code){
                $inv=$this->one('SELECT * FROM invites WHERE hash=? AND used=0 AND expires>?',[hash('sha256',$code),time()]);
                if(!$inv)throw new RedwoodError(403,'An unused invitation is required');
                if($this->one('SELECT id FROM users WHERE username=?',[$username]))throw new RedwoodError(409,'Choose a different username');
                $u=['id'=>bin2hex(random_bytes(16)),'username'=>$username,'password'=>$hash,'role'=>$inv['role']];
                $this->execute('INSERT INTO users VALUES (?,?,?,?)',array_values($u));
                $this->execute('UPDATE invites SET used=1 WHERE hash=?',[hash('sha256',$code)]);return $u;
            });
        } else {
            $user=$this->one('SELECT * FROM users WHERE username=?',[$username]);
            // Equal-cost verification even when a username does not exist.
            $dummy='$argon2id$v=19$m=65536,t=4,p=1$MXhtRnA0blNFZEViTGY3SA$lsrxnw44HiimNoMEvLWKKgCoN1KtYYTRfpAiFP2MOwo';
            if(!password_verify($password,$user['password']??$dummy)||!$user)throw new RedwoodError(401,'Username or password was not accepted');
        }
        return $this->session($user);
    }
    public static function id(string $id): string {if(!preg_match('/^[A-Za-z0-9_-]{1,80}$/D',$id))throw new RedwoodError(400,'Invalid grouping ID');return $id;}
    public static function hash(string $hash): string {if(!preg_match('/^[a-f0-9]{64}$/D',$hash))throw new RedwoodError(400,'Invalid photo hash');return $hash;}
    public function group(string $id): array {
        $row=$this->one('SELECT v.* FROM groups g JOIN versions v ON g.revision=v.revision WHERE g.id=?',[self::id($id)]);
        if(!$row)throw new RedwoodError(404,'Grouping not found');
        return ['id'=>$id,'revision'=>(int)$row['revision'],'fingerprint'=>$row['fingerprint'],'body'=>$row['body'],'photos'=>json_decode($row['photos'],true,64,JSON_THROW_ON_ERROR)];
    }
    public function changes(int $cursor): array {
        $s=$this->db->prepare('SELECT id,revision FROM groups WHERE revision>? ORDER BY revision LIMIT 50');$s->execute([$cursor]);
        $items=[];while($r=$s->fetch(PDO::FETCH_ASSOC))$items[]=['id'=>$r['id'],'revision'=>(int)$r['revision']];
        return ['changes'=>$items];
    }
    public function save(array $input,array $user): array {
        $id=self::id((string)($input['id']??''));$body=$input['body']??null;$photos=$input['photos']??null;$base=$input['base']??null;
        if(!is_string($body)||strlen($body)>self::DOCUMENT_LIMIT||!is_int($base)||$base<0||!is_array($photos)||!array_is_list($photos)||count($photos)>64)throw new RedwoodError(400,'Invalid grouping');
        try{$g=json_decode($body,true,64,JSON_THROW_ON_ERROR);}catch(Throwable $e){throw new RedwoodError(400,'Invalid grouping JSON');}
        if(!is_array($g)||($g['id']??null)!==$id||($g['schema']??0)!==4||!isset($g['target_preset'])||!is_array($g['views']??null)||count($g['views'])>64)throw new RedwoodError(400,'Unsupported grouping');
        if(!empty($g['pending_photo_cleanup']))throw new RedwoodError(400,'Device cleanup paths cannot be synced');
        $files=[];$clean=[];
        foreach($photos as $p){
            if(!is_array($p)||!is_string($p['file']??null)||!preg_match('/^[A-Za-z0-9_-]{1,80}\.jpg$/D',$p['file'])||isset($files[$p['file']]))throw new RedwoodError(400,'Invalid photo name');
            $hash=self::hash((string)($p['sha256']??''));$blob=$this->one('SELECT bytes FROM blobs WHERE hash=?',[$hash]);
            if(!$blob || (int)$blob['bytes']!==($p['bytes']??null))throw new RedwoodError(400,'Photo upload is incomplete');
            $files[$p['file']]=$hash;$clean[]=['file'=>$p['file'],'sha256'=>$hash,'bytes'=>(int)$blob['bytes']];
        }
        $references=[];
        foreach($g['views'] as $v){if(!is_array($v)||!isset($files[$v['file']??''])||isset($references[$v['file']]))throw new RedwoodError(400,'Every view needs its own verified photo');$references[$v['file']]=true;}
        if(count($files)!==count($references))throw new RedwoodError(400,'Unreferenced photo');
        usort($clean,fn($a,$b)=>strcmp($a['file'],$b['file']));
        // The exact serialized body is retained. The client hashes these same bytes.
        $fingerprint=hash('sha256',$body."\n".implode("\n",array_map(fn($p)=>$p['file'].':'.$p['sha256'].':'.$p['bytes'],$clean)));
        if(!hash_equals($fingerprint,(string)($input['fingerprint']??'')))throw new RedwoodError(400,'Grouping checksum mismatch');
        return $this->transaction(function()use($id,$body,$clean,$base,$fingerprint,$user){
            $old=$this->one('SELECT v.* FROM groups g JOIN versions v ON g.revision=v.revision WHERE g.id=?',[$id]);
            if($old&&hash_equals($old['fingerprint'],$fingerprint))return ['revision'=>(int)$old['revision'],'fingerprint'=>$fingerprint];
            if(($old?(int)$old['revision']:0)!==$base)throw new RedwoodError(409,'Both phones changed this grouping; neither version was overwritten');
            // Photo IDs are immutable, including across historical revisions.
            $s=$this->db->prepare('SELECT photos FROM versions WHERE group_id=?');$s->execute([$id]);
            while($row=$s->fetch(PDO::FETCH_ASSOC)){foreach(json_decode($row['photos'],true) as $prior)foreach($clean as $p)if($prior['file']===$p['file']&&$prior['sha256']!==$p['sha256'])throw new RedwoodError(409,'A photo ID cannot be reused for different pixels');}
            $this->execute('INSERT INTO versions(group_id,fingerprint,body,photos,author,modified) VALUES (?,?,?,?,?,?)',[$id,$fingerprint,$body,json_encode($clean,JSON_THROW_ON_ERROR),$user['id'],time()]);
            $revision=(int)$this->db->lastInsertId();$this->execute('INSERT INTO groups VALUES (?,?) ON CONFLICT(id) DO UPDATE SET revision=excluded.revision',[$id,$revision]);
            return ['revision'=>$revision,'fingerprint'=>$fingerprint];
        });
    }
    public function putPhoto(string $hash,$input): array {
        self::hash($hash);$existing=$this->one('SELECT bytes FROM blobs WHERE hash=?',[$hash]);if($existing)return ['bytes'=>(int)$existing['bytes']];
        $temp=tempnam($this->directory.'/blobs','.upload-');chmod($temp,0600);
        try {
            $out=fopen($temp,'wb');try{$bytes=stream_copy_to_stream($input,$out,self::PHOTO_LIMIT+1);fflush($out);fsync($out);}finally{fclose($out);}
            if(!$bytes||$bytes>self::PHOTO_LIMIT||!hash_equals($hash,hash_file('sha256',$temp)))throw new RedwoodError(400,'Incomplete or oversized photo');
            $size=@getimagesize($temp);
            if(!$size||$size[2]!==IMAGETYPE_JPEG||$size[0]>4096||$size[1]>4096)throw new RedwoodError(400,'Expected a bounded JPEG image');
            $this->transaction(function()use($hash,$temp,$bytes){
                if($this->one('SELECT hash FROM blobs WHERE hash=?',[$hash]))return;
                if((int)$this->one('SELECT COALESCE(SUM(bytes),0) total FROM blobs')['total']+$bytes>2147483648)throw new RedwoodError(507,'Shared photo storage is full. Offline data is safe.');
                if(!rename($temp,$this->directory.'/blobs/'.$hash))throw new RuntimeException('Photo commit failed');
                $this->execute('INSERT INTO blobs VALUES (?,?)',[$hash,$bytes]);
            });return ['bytes'=>$bytes];
        }finally{if(is_file($temp))unlink($temp);}
    }
    public function dispatch(string $action,string $method,array $input,array $query,string $token,string $ip): array {
        if($action==='health'&&$method==='GET')return ['service'=>'Redwood Arrows','protocol'=>1,'private'=>true];
        if(in_array($action,['signup','login'],true)&&$method==='POST')return $this->account($action,$input,$ip);
        $user=$this->authenticate($token);
        if($action==='me'&&$method==='GET')return $this->identity($user);
        if($action==='logout'&&$method==='POST'){$this->execute('DELETE FROM tokens WHERE hash=?',[hash('sha256',$token)]);return ['ok'=>true];}
        if($action==='password'&&$method==='POST'){
            $this->rate('password:'.$user['id'],10,900);
            if(!password_verify((string)($input['old_password']??''),$user['password']))throw new RedwoodError(403,'Current password was not accepted');
            $next=(string)($input['password']??'');self::password($next);$hash=password_hash($next,PASSWORD_ARGON2ID);
            $this->transaction(function()use($user,$hash){$this->execute('UPDATE users SET password=? WHERE id=?',[$hash,$user['id']]);$this->execute('DELETE FROM tokens WHERE user_id=?',[$user['id']]);});return $this->session($user);
        }
        if($action==='invite'&&$method==='POST'){
            if($user['role']!=='owner')throw new RedwoodError(403,'Only the library owner can invite people');
            $this->rate('invite:'.$user['id'],10,86400);return ['invite'=>$this->invite(),'expires_in_days'=>7];
        }
        if($action==='changes'&&$method==='GET')return $this->changes(max(0,(int)($query['after']??0)));
        if($action==='group'&&$method==='GET')return $this->group((string)($query['id']??''));
        if($action==='save'&&$method==='POST')return $this->save($input,$user);
        if($action==='missing'&&$method==='POST'){
            $hashes=$input['hashes']??null;if(!is_array($hashes)||count($hashes)>64)throw new RedwoodError(400,'Invalid photo list');$missing=[];
            foreach($hashes as $hash)if(!$this->one('SELECT hash FROM blobs WHERE hash=?',[self::hash((string)$hash)]))$missing[]=$hash;return ['missing'=>$missing];
        }
        throw new RedwoodError(404,'Unknown request');
    }
}

function redwood_http(string $directory): void {
    header('Cache-Control: no-store, private');header('X-Content-Type-Options: nosniff');header('Referrer-Policy: no-referrer');header("Content-Security-Policy: default-src 'none'; frame-ancestors 'none'");
    try {
        if(($_SERVER['HTTPS']??'')!=='on'&&getenv('REDWOOD_TEST_HTTP')!=='1')throw new RedwoodError(403,'HTTPS is required');
        $app=new Redwood($directory);$action=(string)($_GET['action']??'health');$method=$_SERVER['REQUEST_METHOD'];
        $auth=$_SERVER['HTTP_AUTHORIZATION']??$_SERVER['REDIRECT_HTTP_AUTHORIZATION']??'';
        $token=str_starts_with($auth,'Bearer ')?substr($auth,7):'';
        if($action==='photo'){
            $app->authenticate($token);$hash=Redwood::hash((string)($_GET['hash']??''));
            if($method==='PUT'){$in=fopen('php://input','rb');try{$result=$app->putPhoto($hash,$in);}finally{fclose($in);}}
            elseif($method==='GET'){
                $row=$app->one('SELECT bytes FROM blobs WHERE hash=?',[$hash]);if(!$row)throw new RedwoodError(404,'Photo not found');
                header('Content-Type: image/jpeg');header('Content-Length: '.$row['bytes']);readfile($directory.'/blobs/'.$hash);return;
            }else throw new RedwoodError(405,'Method not allowed');
        }else{
            $input=[];
            if($method==='POST'){
                if(strtolower(explode(';',$_SERVER['CONTENT_TYPE']??'')[0])!=='application/json')throw new RedwoodError(415,'JSON is required');
                $body=file_get_contents('php://input',false,null,0,2*Redwood::DOCUMENT_LIMIT+1);
                if(strlen($body)>2*Redwood::DOCUMENT_LIMIT)throw new RedwoodError(413,'Request too large');
                try{$input=json_decode($body,true,64,JSON_THROW_ON_ERROR);}catch(Throwable $e){throw new RedwoodError(400,'Invalid JSON');}
                if(!is_array($input))throw new RedwoodError(400,'Invalid request');
            }
            $result=$app->dispatch($action,$method,$input,$_GET,$token,$_SERVER['REMOTE_ADDR']??'unknown');
        }
        header('Content-Type: application/json');echo json_encode($result,JSON_THROW_ON_ERROR|JSON_UNESCAPED_SLASHES);
    }catch(RedwoodError $e){http_response_code($e->status);header('Content-Type: application/json');if($e->status===429)header('Retry-After: 900');echo json_encode(['error'=>$e->getMessage()]);}
    catch(Throwable $e){error_log('Redwood internal error: '.get_class($e));http_response_code(500);header('Content-Type: application/json');echo '{"error":"Sync could not finish. Your offline data is safe."}';}
}
