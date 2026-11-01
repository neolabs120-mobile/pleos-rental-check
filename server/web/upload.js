(() => {
  const categories = [
    {key:'windows', name:'창문 상태', hint:'창문 상태를 확인할 수 있게 촬영해 주세요.'},
    {key:'belongings', name:'실내 · 소지품', hint:'좌석과 트렁크 등 소지품을 확인한 곳을 촬영해 주세요.'},
    {key:'exterior', name:'외관 상태', hint:'차량 외관과 기록할 흠집 부위를 촬영해 주세요.'}
  ];
  const params = new URLSearchParams(location.hash.slice(1));
  const storedToken=()=>{try{return sessionStorage.getItem('rental-photo-token') || '';}catch{return '';}};
  let token = params.get('token') || storedToken();
  const accountToken=()=>{try{return sessionStorage.getItem('rental-account-token') || '';}catch{return '';}};
  if (params.has('token')) {
    try {sessionStorage.removeItem('rental-photo-last-receipt');} catch {}
    try {sessionStorage.setItem('rental-photo-token', token);} catch {}
    history.replaceState(null, '', location.pathname);
  }
  const notice = document.getElementById('notice');
  const container = document.getElementById('photos');
  const pending = new Map();
  const objectUrls = [];
  let current = null;
  let busy = false;
  let checksDirty = false;
  let submitted = false;
  const submitButton=document.getElementById('submit');
  function updateSubmit() {
    const ready=current && current.phase==='return' && categories.every(c=>current.checks[c.key] && current.photos.some(p=>p.category===c.key));
    submitButton.hidden=!current || current.phase!=='return';
    submitButton.disabled=busy || submitted || checksDirty || pending.size>0 || !ready;
  }
  for (const input of document.querySelectorAll('#check-panel input,#note')) input.addEventListener('input',()=>{ checksDirty=true; updateSubmit(); });
  function message(text, error=false) { notice.textContent=text; notice.dataset.error=String(error); }
  async function api(path, method='GET', body) {
    const response=await fetch(path,{method,headers:{Authorization:'Bearer '+token,'X-Account-Token':accountToken(),...(body?{'Content-Type':'application/json'}:{})},body:body?JSON.stringify(body):undefined,credentials:'omit',cache:'no-store'});
    const data=await response.json();
    if(!response.ok) {
      if(response.status===401) { location.replace('/account'); }
      throw new Error(typeof data.detail==='string'?data.detail:'요청을 완료하지 못했어요. 다시 시도해 주세요.');
    }
    return data;
  }
  const uuid=()=>{
    if(crypto.randomUUID) return crypto.randomUUID();
    const bytes=crypto.getRandomValues(new Uint8Array(16)); bytes[6]=(bytes[6]&15)|64; bytes[8]=(bytes[8]&63)|128;
    const hex=Array.from(bytes,x=>x.toString(16).padStart(2,'0')).join('');
    return [hex.slice(0,8),hex.slice(8,12),hex.slice(12,16),hex.slice(16,20),hex.slice(20)].join('-');
  };
  async function work(action) {
    if(busy) return;
    busy=true; document.querySelectorAll('button,input[type=file]').forEach(e=>e.disabled=true);
    try { await action(); } catch(error) { message(error.message || '전송을 완료하지 못했어요.',true); }
    finally {
      busy=false;
      document.querySelectorAll('button,input[type=file]').forEach(e=>e.disabled=false);
      updateSubmit();
      if(submitted) document.querySelectorAll('button,input,textarea').forEach(e=>e.disabled=true);
    }
  }
  function fileBase64(file) {
    return new Promise((resolve,reject)=>{const reader=new FileReader(); reader.onload=()=>resolve(String(reader.result).split(',')[1]);reader.onerror=()=>reject(new Error('사진을 읽지 못했어요.'));reader.readAsDataURL(file);});
  }
  async function refresh() {
    current=await api('/api/upload/session');
    document.getElementById('phase').textContent=current.phase==='pickup'?'인수 사진':'반납 사진';
    document.getElementById('session').textContent=(current.isDemo?'합성 시연 데이터 · 실제 차량 아님 · ':'')+'VIN ···'+current.vinSuffix+' · 링크 만료 '+new Date(current.expiresAtMillis).toLocaleTimeString('ko-KR');
    document.getElementById('check-panel').hidden=false;
    if(!checksDirty) {
      categories.forEach(c=>document.getElementById('check-'+c.key).checked=Boolean(current.checks[c.key]));
      document.getElementById('note').value=current.checks.note || '';
    }
    for(const url of objectUrls.splice(0)) URL.revokeObjectURL(url);
    container.replaceChildren();
    for(const category of categories) {
      const section=document.createElement('section'); section.className='panel';
      const heading=document.createElement('h2'); heading.textContent=category.name;
      const hint=document.createElement('p'); hint.textContent=category.hint;
      const count=document.createElement('span'); count.className='count';
      const photos=current.photos.filter(p=>p.category===category.key);
      count.textContent='서버에 전송됨 '+photos.length+' / 최대 5장';
      section.append(heading,hint,count);
      const actions=document.createElement('div'); actions.className='actions';
      for(const camera of [true,false]) {
        const label=document.createElement('label'); label.className='file-label';
        const input=document.createElement('input'); input.type='file'; input.accept='image/jpeg,image/png,image/webp';
        input.setAttribute('aria-label',category.name+(camera?' 촬영':' 사진 선택'));
        if(camera) input.setAttribute('capture','environment');
        label.textContent=camera?'카메라 촬영':'사진 선택';
        input.addEventListener('change',()=>{
          const file=input.files[0]; if(!file) return;
          if(file.size>8*1024*1024) { message('사진은 8MB 이하여야 해요.',true); return; }
          pending.set(category.key,{file,requestId:uuid()}); renderPending(); updateSubmit();
        });
        label.append(input); actions.append(label);
      }
      section.append(actions);
      const pendingArea=document.createElement('div');
      function renderPending() {
        pendingArea.replaceChildren(); const entry=pending.get(category.key); if(!entry) return;
        const image=document.createElement('img'); image.className='pending-image'; image.alt=category.name+' 전송 전 미리보기';
        image.src=URL.createObjectURL(entry.file); objectUrls.push(image.src);
        const send=document.createElement('button'); send.type='button'; send.className='send'; send.textContent='이 사진 전송';
        send.addEventListener('click',()=>work(async()=>{
          message('사진을 서버로 전송하고 있어요.');
          await api('/api/upload/photos','POST',{category:category.key,requestId:entry.requestId,imageBase64:await fileBase64(entry.file)});
          pending.delete(category.key); await refresh(); message('사진이 서버에 저장됐어요.');
        }));
        const cancel=document.createElement('button'); cancel.type='button'; cancel.textContent='선택 취소';
        cancel.addEventListener('click',()=>{pending.delete(category.key);renderPending();updateSubmit();});
        pendingArea.append(image,send,cancel);
      }
      renderPending(); section.append(pendingArea);
      const gallery=document.createElement('div'); gallery.className='gallery';
      for(const photo of photos) {
        const figure=document.createElement('figure'); figure.className='thumb';
        const image=document.createElement('img'); image.alt=category.name+' 전송된 사진';
        const response=await fetch('/api/upload/photos/'+encodeURIComponent(photo.id),{headers:{Authorization:'Bearer '+token,'X-Account-Token':accountToken()},credentials:'omit',cache:'no-store'});
        if(response.ok) {image.src=URL.createObjectURL(await response.blob());objectUrls.push(image.src);}
        const remove=document.createElement('button'); remove.type='button'; remove.textContent='삭제';
        remove.addEventListener('click',()=>work(async()=>{await api('/api/upload/photos/'+encodeURIComponent(photo.id),'DELETE');await refresh();message('사진을 삭제했어요.');}));
        figure.append(image,remove); gallery.append(figure);
      }
      section.append(gallery); container.append(section);
    }
  }
  document.getElementById('save-checks').addEventListener('click',()=>work(async()=>{
    const checks={note:document.getElementById('note').value}; categories.forEach(c=>checks[c.key]=document.getElementById('check-'+c.key).checked);
    await api('/api/upload/checks','PUT',checks); checksDirty=false; await refresh();
    const complete=categories.every(c=>current.checks[c.key] && current.photos.some(p=>p.category===c.key));
    message(complete?(current.phase==='return'?'사진과 확인 내역이 전송됐어요. 최종 제출 버튼으로 마무리해 주세요.':'인수 사진과 확인 내역을 저장했어요.'):'확인 내역을 저장했어요. 빠진 사진과 확인 항목을 마저 채워 주세요.');
  }));
  submitButton.addEventListener('click',()=>work(async()=>{
    const receipt=await api('/api/upload/submit','POST',{revision:current.revision});
    submitted=true;
    try {sessionStorage.setItem('rental-photo-last-receipt',JSON.stringify(receipt));} catch {}
    message('점검 기록 제출을 접수했어요. 접수 번호: '+receipt.receiptId);
    document.getElementById('completion').textContent='서버 접수 시각: '+new Date(receipt.submittedAtMillis).toLocaleString('ko-KR')+' · 렌터카 업체의 실제 반납 처리는 별도로 진행돼요.';
  }));
  let savedReceipt=null;
  if(!params.has('token')) {try {savedReceipt=JSON.parse(sessionStorage.getItem('rental-photo-last-receipt'));} catch {}}
  if(!accountToken()) location.replace('/account');
  else if(savedReceipt && typeof savedReceipt.receiptId==='string' && savedReceipt.submittedAtMillis>0) {
    work(async()=>{
      const response=await fetch('/api/account/rentals/'+encodeURIComponent(savedReceipt.id),{headers:{Authorization:'Bearer '+accountToken()},credentials:'omit',cache:'no-store'});
      if(!response.ok) {location.replace('/account');return;}
      const receipt=await response.json();
      if(!receipt.submittedAtMillis || !receipt.receiptId)throw new Error('서버 접수 내역을 확인하지 못했어요. 내 이용 기록에서 다시 확인해 주세요.');
      submitted=true;
      document.querySelector('h1').textContent='점검 기록을 제출했어요';
      document.getElementById('phase').textContent='최근 접수 기록';
      document.getElementById('session').textContent='서버 접수 시각: '+new Date(receipt.submittedAtMillis).toLocaleString('ko-KR');
      message('접수 번호: '+receipt.receiptId);
      document.querySelector('footer').textContent='렌터카 업체의 실제 반납 처리는 별도로 진행돼요.';
    });
  }
  else if(token) work(async()=>{await refresh();message('사진 선택 후 ‘이 사진 전송’을 눌러 주세요.');});
  else message('차량 화면에서 사진 전송 링크를 발급받아 QR로 다시 접속해 주세요.',true);
})();
