(() => {
  'use strict';
  const screen=document.getElementById('screen'), notice=document.getElementById('notice');
  const read=key=>{try{return sessionStorage.getItem(key)||'';}catch{return '';}};
  const save=(key,value)=>{try{value?sessionStorage.setItem(key,value):sessionStorage.removeItem(key);}catch{throw new Error('이 브라우저에서 임시 저장을 허용한 뒤 다시 이용해 주세요.');}};
  const params=new URLSearchParams(location.hash.slice(1));
  if(params.has('pair')) save('rental-pending-pair',params.get('pair'));
  if(params.has('upload')) {save('rental-photo-token',params.get('upload'));save('rental-photo-last-receipt','');}
  history.replaceState(null,'',location.pathname);
  let token=read('rental-account-token'), user=null, busy=false, challenge=null, currentRoute='', photoUrls=[];
  try {challenge=JSON.parse(read('rental-account-challenge')||'null');} catch {}
  const message=(text,error=false)=>{notice.textContent=text;notice.dataset.error=String(error);};
  async function api(path,method='GET',body,extra={}) {
    const response=await fetch(path,{method,headers:{Authorization:'Bearer '+token,...extra,...(body?{'Content-Type':'application/json'}:{})},body:body?JSON.stringify(body):undefined,credentials:'omit',cache:'no-store'});
    const data=await response.json();
    if(!response.ok) {
      if(response.status===401 && path.startsWith('/api/account/')) {token='';user=null;save('rental-account-token','');await route('login');}
      throw new Error(typeof data.detail==='string'?data.detail:'입력한 내용을 확인하고 다시 시도해 주세요.');
    }
    return data;
  }
  async function work(action) {
    if(busy)return;
    busy=true;document.querySelectorAll('button').forEach(e=>e.disabled=true);
    try{await action();}catch(error){message(error.message||'요청을 완료하지 못했어요.',true);}
    finally{busy=false;document.querySelectorAll('button').forEach(e=>e.disabled=false);}
  }
  const el=(tag,text,className)=>{const node=document.createElement(tag);if(text!==undefined)node.textContent=text;if(className)node.className=className;return node;};
  const button=(text,action)=>{const node=el('button',text);node.type='button';node.addEventListener('click',()=>work(action));return node;};
  function form(id,action){document.getElementById(id).addEventListener('submit',event=>{event.preventDefault();const data=new FormData(event.target);work(()=>action(data));});}
  function samePassword(data){if(data.get('password')!==data.get('confirm'))throw new Error('비밀번호 확인이 일치하지 않습니다.');}
  function rememberChallenge(result,email,purpose){challenge={...result,email,purpose};save('rental-account-challenge',JSON.stringify(challenge));}
  const time=value=>value?new Date(value).toLocaleString('ko-KR'):'아직 기록 없음';
  async function route(name) {
    currentRoute=name;message('');photoUrls.splice(0).forEach(url=>URL.revokeObjectURL(url));
    screen.replaceChildren(document.getElementById(name).content.cloneNode(true));
    screen.querySelectorAll('[data-route]').forEach(node=>node.addEventListener('click',()=>work(()=>route(node.dataset.route))));
    screen.querySelectorAll('[data-password]').forEach(node=>node.addEventListener('click',()=>{
      const input=screen.querySelector('[name='+node.dataset.password+']');input.type=input.type==='password'?'text':'password';node.textContent=input.type==='password'?'비밀번호 보기':'비밀번호 숨기기';
    }));
    screen.querySelectorAll('[data-policy]').forEach(node=>node.addEventListener('click',()=>{
      const privacy=node.dataset.policy==='privacy';
      document.getElementById('policy-title').textContent=privacy?'개인정보 안내 · 공모전 시연 초안':'이용약관 · 공모전 시연 초안';
      document.getElementById('policy-text').textContent=privacy?
        '계정 생성과 점검 기록 연결을 위해 닉네임, 이메일, 해시 처리한 비밀번호와 동의 시각을 보관해요. 대여 건의 차량 관측값, 사진, 직접 확인 내역은 해당 계정에서 확인할 수 있어요.\n이 문서는 공모전 시연용 초안이에요. 실제 서비스 전 운영 주체, 보관 기간, 탈퇴·삭제 절차를 확정해야 해요. 실제 개인정보 대신 시험 계정으로 이용해 주세요.':
        '본 서비스는 차량 인수·반납 시 관측 기록과 사진을 보관해요. 사진으로 손상이나 분실을 자동 판정하지 않으며 렌터카 업체의 반납·결제를 대신하지 않아요.\n공모전 시연용 초안이며 실제 서비스 약관이 아니에요.';
      document.getElementById('policy').showModal();
    }));
    if(name==='signup')form('signup-form',async data=>{
      samePassword(data);
      const result=await api('/api/auth/signup','POST',{nickname:data.get('nickname'),email:data.get('email'),password:data.get('password'),termsAccepted:data.has('terms'),privacyAccepted:data.has('privacy')});
      rememberChallenge(result,data.get('email'),'signup');await route('verify');
    });
    if(name==='verify') {
      if(!challenge || challenge.purpose!=='signup')return route('signup');
      document.getElementById('verify-email').textContent=challenge.email;
      if(challenge.deliveryMode==='file')document.getElementById('delivery-notice').textContent='개발 모드: 확인번호를 서버의 로컬 메일함에 저장했어요. 실제 이메일은 발송하지 않았어요. 10분 안에 입력해 주세요.';
      form('verify-form',async data=>{await api('/api/auth/verify','POST',{challengeId:challenge.challengeId,code:data.get('code')});save('rental-account-challenge','');challenge=null;await route('login');message('회원가입이 완료됐어요. 로그인해 주세요.');});
      document.getElementById('resend').addEventListener('click',()=>work(async()=>{const result=await api('/api/auth/resend','POST',{challengeId:challenge.challengeId});rememberChallenge(result,challenge.email,'signup');message(result.deliveryMode==='file'?'새 확인번호를 개발용 메일함에 저장했어요.':'새 확인번호를 보냈어요.');}));
    }
    if(name==='login')form('login-form',async data=>{
      const result=await api('/api/auth/login','POST',{email:data.get('email'),password:data.get('password')});
      token=result.token;user=result.user;save('rental-account-token',token);await route('home');
    });
    if(name==='reset')form('reset-request',async data=>{const result=await api('/api/auth/password/request','POST',{email:data.get('email')});rememberChallenge(result,data.get('email'),'reset');await route('reset-verify');message(result.deliveryMode==='file'?'개발 모드: 실제 이메일 대신 서버의 로컬 메일함에 확인번호를 저장했어요.':result.message);});
    if(name==='reset-verify') {
      if(!challenge || challenge.purpose!=='reset')return route('reset');
      form('reset-form',async data=>{samePassword(data);await api('/api/auth/password/reset','POST',{challengeId:challenge.challengeId,code:data.get('code'),password:data.get('password')});save('rental-account-challenge','');challenge=null;token='';save('rental-account-token','');await route('login');message('비밀번호를 바꿨어요. 새 비밀번호로 로그인해 주세요.');});
    }
    if(name==='claim')form('claim-form',async data=>{await api('/api/account/bookings/claim','POST',{bookingNumber:data.get('bookingNumber'),claimCode:data.get('claimCode').trim()});await route('home');message('대여 건을 내 계정에 연결했어요.');});
    if(name==='home')await home();
  }
  // Discover the verified recipient's reservations on every visit while keeping vehicle approval explicit and manual codes secondary.
  async function home() {
    if(!token)return route('login');
    user=await api('/api/account/me');
    document.getElementById('greeting').textContent=user.nickname+'님 · '+user.email;
    document.getElementById('logout').addEventListener('click',()=>work(async()=>{
      await api('/api/auth/logout','POST',{});token='';user=null;
      for(const key of ['rental-account-token','rental-account-challenge','rental-photo-token','rental-photo-last-receipt','rental-pending-pair'])save(key,'');
      await route('welcome');message('로그아웃하고 연결된 차량도 해제했어요.');
    }));
    const pair=read('rental-pending-pair'), upload=read('rental-photo-token');
    document.getElementById('pair-banner').hidden=!pair;
    document.getElementById('cancel-pair').addEventListener('click',()=>{save('rental-pending-pair','');work(()=>route('home'));});
    document.getElementById('upload-banner').hidden=!upload;
    document.getElementById('resume-upload').addEventListener('click',()=>work(async()=>{
      await api('/api/upload/session','GET',undefined,{Authorization:'Bearer '+upload,'X-Account-Token':token});location.assign('/upload');
    }));
    const data=await api('/api/account/bookings');
    const container=document.getElementById('bookings');
    if(!data.bookings.length)container.append(el('p','표시할 예약이 없어요. 예약에 사용한 이메일로 로그인했는지 확인하고 새로고침해 주세요.'));
    for(const booking of data.bookings) {
      const card=el('section',undefined,'panel booking');
      card.append(el('span',booking.isDemo?'합성 시연 데이터 · 실제 차량 아님':booking.submittedAtMillis?'점검 제출됨':'이용 기록', 'badge'),el('h2',booking.vehicleName),el('p','예약 '+booking.bookingNumber),el('p','VIN ···'+booking.vinSuffix));
      if(pair && !booking.submittedAtMillis)card.append(button('이 차량 연결 승인',async()=>{
        await api('/api/account/pairings/approve','POST',{token:pair,bookingId:booking.id});save('rental-pending-pair','');await route('home');message('차량 연결을 승인했어요. 차량 화면에서 인수 점검을 시작해 주세요.');
      }));
      if(booking.rentalId) {
        card.append(button('내 점검 기록 보기',()=>detail(booking.rentalId)));
        if(!booking.submittedAtMillis)card.append(button('사진 전송 이어가기',async()=>{
          const record=await api('/api/account/rentals/'+encodeURIComponent(booking.rentalId));
          const link=await api('/api/account/rentals/'+encodeURIComponent(booking.rentalId)+'/photo-links','POST',{phase:record.returnAtMillis?'return':'pickup'});
          const photoToken=new URLSearchParams(new URL(link.uploadUrl).hash.slice(1)).get('token');
          save('rental-photo-token',photoToken);save('rental-photo-last-receipt','');location.assign('/upload');
        }));
      } else card.append(el('p','차량에서 인수 상태를 저장하면 점검 기록이 표시돼요.'));
      container.append(card);
    }
  }
  async function detail(id) {
    const record=await api('/api/account/rentals/'+encodeURIComponent(id));await route('detail');
    const summary=document.getElementById('record-summary');
    summary.append(el('span',record.submittedAtMillis?'서버 접수 완료':'작성 중','badge'),el('h2','VIN ···'+record.vinSuffix),el('p','인수 '+time(record.pickupAtMillis)),el('p','반납 점검 '+time(record.returnAtMillis)));
    if(record.isDemo)summary.append(el('p','합성 시연 데이터 · 실제 차량 아님'));
    if(record.receiptId)summary.append(el('p','접수 번호 '+record.receiptId));
    summary.append(el('p','이용 거리 '+(record.distanceKm===null?'비교 불가':record.distanceKm.toFixed(1)+' km')));
    if(record.pickupFuel!==null && record.returnFuel!==null)summary.append(el('p','연료 '+record.pickupFuel+'% → '+record.returnFuel+'%'));
    const photos=document.getElementById('record-photos');
    for(const [phase,label] of [['pickup','인수'],['return','반납']]) {
      photos.append(el('h2',label));
      for(const [key,title] of [['windows','창문 상태'],['belongings','실내·소지품'],['exterior','외관 상태']]) {
        const entries=record.photos.filter(photo=>photo.phase===phase && photo.category===key);
        photos.append(el('p',title+' · 사진 '+entries.length+'장 · '+(record.checks[phase]?.[key]?'직접 확인 완료':'직접 확인 전')));
        for(const photo of entries) {
          const response=await fetch('/api/account/photos/'+encodeURIComponent(photo.id),{headers:{Authorization:'Bearer '+token},credentials:'omit',cache:'no-store'});
          if(!response.ok)throw new Error('사진을 불러오지 못했어요. 다시 로그인해 주세요.');
          const image=el('img',undefined,'record-photo');image.alt=label+' '+title;image.src=URL.createObjectURL(await response.blob());photoUrls.push(image.src);photos.append(image);
        }
      }
      if(record.checks[phase]?.note)photos.append(el('p','메모: '+record.checks[phase].note));
    }
  }
  document.getElementById('close-policy').addEventListener('click',()=>document.getElementById('policy').close());
  window.addEventListener('pageshow',event=>{if(event.persisted)location.reload();});
  work(()=>route(token?'home':challenge?.purpose==='signup'?'verify':challenge?.purpose==='reset'?'reset-verify':'welcome'));
})();
