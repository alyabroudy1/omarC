


var w=window.innerWidth || document.documentElement.clientWidth || document.body.clientWidth;
var h=window.innerHeight|| document.documentElement.clientHeight|| document.body.clientHeight;
if(w >= 992)
    $(".main_container").css("min-height", h-140)
else
    $(".main_container").css("min-height", "auto");

$('nav#menu').css("display", "block");

$(document).ready(function () {

    $('nav#menu').mmenu({
        offCanvas: {
            position  : "right",
            zposition : "front"
            }
        });
    $(".mm-menu").css("display", "block");

    $('ul.nav li.dropdown').hover(function() {
        $(this).find('.dropdown-menu').stop(true, true).delay(200).fadeIn(300);
    }, function() {
        $(this).find('.dropdown-menu').stop(true, true).delay(200).fadeOut(300);
    });


    var fieldSearch= "title";
    if(LangSearch != 'ar')
        fieldSearch=fieldSearch+"_"+LangSearch;
    //console.log(fieldSearch);
    $('#searchbox').selectize({
        valueField: 'url',
        labelField: 'fieldSearch',
        searchField: ['title' , 'title_en'],
        maxOptions: 8,
        options: [],
        persist:false,
        create: false,
        render: {
            option: function(item, escape) {

                return '<div>' +escape(item[fieldSearch])+'</div>';
            }
        },
        optgroups: [
            {value: 'Episode', label: SeriesStr},
            //{value: 'Actor', label: ActorsStr}
        ],
        optgroupField: 'class',
        highlight: false,
        openOnFocus: false,
        lockOptgroupOrder: true,
        load: function(query, callback) {
            if (!query.length) return callback();
            $.ajax({
                url: root+'/'+LangSearch+'/search',
                type: 'GET',
                dataType: 'json',
                data: {
                    q: query
                },
                error: function() {
                    callback();
                },
                success: function(res) {
                    callback(res.data);
                }
            });
        },
        onChange: function(){
            window.location = this.items[0];
        } });
    checkCookie();
    $(".android .closeX").click(function(){
         $(".android").css("display","none");
        setCookie("app_alert", "true", 356);
    });

    $('[data-toggle="popover"]').each(function () {
        var $elem = $(this);
        $elem.popover({
            placement : 'auto right',
            html:true,
            trigger:'manual',
            content:'adsad',
            container: 'body'


        }).on("mouseenter", function () {
            setTimeout(function () {
                if($elem.is(":hover")){
                    var _this = this;
                    var series_id=$elem.attr('id').substring(7);

                    cont=$elem.attr('data-content');
                    if($(cont).find(".not_logged").length){
                        $elem.popover("show");
                        $(".popover").on("mouseleave", function () {
                            $elem.popover('hide');
                        });
                    }
                    else{
                        $.get("/favorite/is_add?id="+series_id, function(data) {
                            if(data[0].status=="success"){
                                // alert($elem.attr('id').substring(7));
                                $elem.popover("show");
                                var old_cont=$elem.data('bs.popover').tip().find(".popover-content").html();
                                if(data[0].added==true)
                                    var new_cont ="<a href='javascript:;' onclick='setFav(this)' class='btn  btn-fav btn-md seeNow innerBookmark remove' id='"+series_id+"'>"+deleteStr+"</a>";
                                if(data[0].added==false)
                                    var new_cont ="<a href='javascript:;' onclick='setFav(this)' class='btn  btn-fav btn-md seeNow innerBookmark add' id='"+series_id+"'>"+AddFavStr+"</a>";
                                $elem.data('bs.popover').tip().find(".popover-content").html(old_cont+new_cont);
                            }
                            $(".popover").on("mouseleave", function () {
                                $elem.popover('hide');
                            });
                        });
                    }

                }

            }, 500);



        }).on("mouseleave", function () {
            var _this = this;
            setTimeout(function () {
                if (!$(".popover:hover").length) {
                    $(_this).popover("hide");
                }
            }, 100);
        });
    });

});
function setFav (elem){
    if($(elem).hasClass( "remove" )) {
        $.get("/favorite/delete?id=" + $(elem).attr("id"), function (data) {

            if (data[0].status == "success") {

                var pid=$(elem).parents('.popover').attr('id');
                elememt='.slick-slide[aria-describedby='+pid+']';
                contentpop=$(elememt).attr("data-content");

                temp_elem=$(contentpop);
                temp_elem.find('.innerBookmark').html(AddFavStr);
                temp_elem.find('.innerBookmark').removeClass("remove");
                temp_elem.find('.innerBookmark').addClass("add");
                $(elememt).attr("data-content","<div>"+temp_elem.html()+"</div>");


                $(elem).html(AddFavStr);
                $(elem).removeClass("remove");
                $(elem).addClass("add");


            }

        });
    }
    else{
        $.get("/favorite/add?id="+$(elem).attr("id"), function(data) {
            if(data[0].status=="success"){
                var pid=$(elem).parents('.popover').attr('id');
                elememt='.slick-slide[aria-describedby='+pid+']';
                contentpop=$(elememt).attr("data-content");

                temp_elem=$(contentpop);
                temp_elem.find('.innerBookmark').html(deleteStr);
                temp_elem.find('.innerBookmark').removeClass("add");
                temp_elem.find('.innerBookmark').addClass("remove");
                $(elememt).attr("data-content","<div>"+temp_elem.html()+"</div>");


                $(elem).html(deleteStr);
                $(elem).removeClass("add");
                $(elem).addClass("remove");

            }

        });
    }

}
function setCookie(cname, cvalue, exdays) {
    var d = new Date();
    d.setTime(d.getTime() + (exdays*24*60*60*1000));
    var expires = "expires="+d.toUTCString();
    document.cookie = cname + "=" + cvalue + "; " + expires;
}

function getCookie(cname) {
    var name = cname + "=";
    var ca = document.cookie.split(';');
    for(var i = 0; i < ca.length; i++) {
        var c = ca[i];
        while (c.charAt(0) == ' ') {
            c = c.substring(1);
        }
        if (c.indexOf(name) == 0) {
            return c.substring(name.length, c.length);
        }
    }
    return "";
}

function checkCookie() {
    var app_alert = getCookie("app_alert");
    if (app_alert != "") {
        $(".android").css("display","none");
    }
    else{
        var ua = navigator.userAgent;
        var checker = {
            iphone: ua.match(/(iPhone|iPod|iPad)/),
            android: ua.match(/Android/)
        };
        if (checker.android){
            $('.android .install a').attr("href", "https://play.google.com/store/apps/details?id=com.watanflix");
            $(".android").css("display","block");
        }
        else if(checker.iphone){
            $('.android .install a').attr("href", "https://itunes.apple.com/us/app/watanflix/id1112436149");
            $(".android").css("display","block");
        }
    }
}


